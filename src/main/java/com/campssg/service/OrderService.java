package com.campssg.service;

import com.campssg.common.S3Uploder;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import com.campssg.DB.entity.*;
import com.campssg.DB.repository.*;
import com.campssg.dto.order.*;
import com.campssg.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional
@RequiredArgsConstructor
public class OrderService {

    private final UserRepository userRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final MartRepository martRepository;
    private final S3Uploder s3Uploder;
    private final RequestedProductRepository requestedProductRepository;

    // 주문 시 주문서 생성하고 주문 정보 반환
    public OrderResponseDto addOrderInfo(OrderRequestDto orderRequestDto) throws IOException, WriterException {
        User user = SecurityUtil.getCurrentUsername().flatMap(userRepository::findByUserEmail).orElseThrow(); // 현재 로그인하고 있는 사용자 정보 가져오기
        Cart cart = cartRepository.findByUser_userId(user.getUserId()).orElseThrow();
        List<CartItem> cartItemList = cartItemRepository.findByCart_cartId(cart.getCartId()); // 장바구니에 있는 상품 목록 가져오기
        Mart mart = cartItemList.get(0).getProduct().getMart(); // cartItem에서 마트 정보 가져오기
        Order order = addOrder(user, mart, cart, orderRequestDto);
        List<OrderItemList> orderItemLists = addOrderItem(cartItemList, order); // cartItemList에 있는 상품 목록 orderItemList로 옮기기
        cart.setTotalCount(0);
        cart.setTotalPrice(0);
        cartRepository.save(cart);
        return new OrderResponseDto(order, orderItemLists);
    }

    // 주문번호로 주문 상세 내역 조회
    public OrderDetailResponseDto getOrderInfo(Long orderId) {
        Order order = orderRepository.findByOrderId(orderId);
        List<OrderItem> orderItemList = orderItemRepository.findByOrder_orderId(orderId);
        List<OrderItemList> orderItemLists = orderItemList.stream().map(orderItem -> new OrderItemList(orderItem)).collect(Collectors.toList());
        List<RequestedProduct> requestedProducts = requestedProductRepository.findByOrder_orderId(orderId).orElse(null);
        if (requestedProducts.isEmpty()) {
            return new OrderDetailResponseDto(order, orderItemLists, null);
        } else {
            List<RequestedProductList> requestedProductLists = requestedProducts.stream().map(requestedProduct -> new RequestedProductList(requestedProduct)).collect(Collectors.toList());
            return new OrderDetailResponseDto(order, orderItemLists, requestedProductLists);
        }
    }

    // 사용자 주문 내역 조회(목록 조회)
    public List<UserOrderListResponseDto> getUserOrderList() {
        User user = SecurityUtil.getCurrentUsername().flatMap(userRepository::findByUserEmail).orElseThrow(); // 현재 로그인하고 있는 사용자 정보 가져오기
        List<Order> orderList = orderRepository.findByUser_userId(user.getUserId());
        return orderList.stream().map(order -> new UserOrderListResponseDto(order)).collect(Collectors.toList());
    }

    // 마트 id로 주문 현황 조회
    public List<MartOrderListResponseDto> getMartOrderList(Long martId) {
        List<Order> orderList = orderRepository.findByMart_martId(martId);
        return orderList.stream().map(order -> new MartOrderListResponseDto(order)).collect(Collectors.toList());
    }

    // 픽업준비완료인 주문 내역 조회
    public List<UserOrderListResponseDto> getPreparedOrderList() {
        User user = SecurityUtil.getCurrentUsername().flatMap(userRepository::findByUserEmail).orElseThrow(); // 현재 로그인하고 있는 사용자 정보 가져오기
        List<Order> orderList = orderRepository.findByUser_userIdAndOrderState(user.getUserId(), OrderState.픽업준비완료);
        return orderList.stream().map(order -> new UserOrderListResponseDto(order)).collect(Collectors.toList());
    }

    // 주문서 생성
    public Order addOrder(User user, Mart mart, Cart cart, OrderRequestDto orderRequestDto)
        throws IOException, WriterException {
        int charge = setCostCharge(cart.getTotalPrice())+setPeriodCharge(orderRequestDto.getReservedAt(), LocalDateTime.now());

        Order order = orderRepository.save(Order.builder()
                .orderId(setOrderNumber())
                .mart(mart)
                .user(user)
                .reservedAt(orderRequestDto.getReservedAt())
                .orderState(OrderState.주문완료)
                .charge(charge)
                .totalPrice(cart.getTotalPrice()+charge)
                .build());

        createQrImg(order);
        return order;
    }

    // 주문서에 상품 추가
    public List<OrderItemList> addOrderItem(List<CartItem> cartItemList, Order order) {
        List<OrderItemList> orderItemLists = new ArrayList<>();
        for (int i=0; i<cartItemList.size(); i++) {
            CartItem cartItem = cartItemList.get(i);
            OrderItem orderItem = orderItemRepository.save(OrderItem.builder()
                    .order(order)
                    .product(cartItem.getProduct())
                    .orderItemCount(cartItem.getCartItemCount())
                    .build());
            cartItemRepository.delete(cartItem);
            OrderItemList orderItemList = new OrderItemList(orderItem);
            orderItemLists.add(orderItemList);
        }
        return orderItemLists;
    }

    // 주문번호 생성
    public Long setOrderNumber() {
        // 주문 날짜와 시간
        String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        // 세자리 난수 생성
        Random random = new Random();
        int number = 0; // 1자리 난수
        String stringNumber = ""; //1자리 난수를 String 으로 형변환
        String resultNumber = ""; // 최종적으로 만들 3자리 난수 string
        for (int i = 0; i < 3; i++) {
            number = random.nextInt(9);
            stringNumber = Integer.toString(number);
            resultNumber += stringNumber;
        }
        String orderNumber = dateTime + resultNumber; // 주문날짜와 시간, 3자리 난수를 합친 주문 번호

        return Long.parseLong(orderNumber);
    }

    // 가격에 따른 수수료
    public int setCostCharge(int cartItemPrice) {
        int charge;
        if (cartItemPrice < 50000) {
            charge = 0;
        } else if (cartItemPrice < 100000) {
            charge = 1000;
        } else if (cartItemPrice < 150000) {
            charge = 1500;
        } else {
            charge = 2000;
        }
        return charge;
    }

    // 예약 기간에 따른 수수료(픽업 예약 날짜 - 주문 날짜 간격)
    public int setPeriodCharge(LocalDateTime reservedAt, LocalDateTime createdAt) {
        long day = ChronoUnit.DAYS.between(createdAt, reservedAt);
        int charge;
        if (day < 1) {
            charge = 0;
        } else if (day < 7) {
            charge = 1000;
        } else if (day < 14) {
            charge = 1500;
        } else {
            charge = 2000;
        }
        return charge;
    }

    public void updateOrderStatus(Long orderId, String status) {
        Order order = orderRepository.findByOrderId(orderId);
        order.updateStatus(OrderState.valueOf(status));
    }

    private void createQrImg(Order order) throws IOException, WriterException, WriterException {
        String url = "127.0.0.1:8080/order/" + order.getOrderId() + "/주문완료";
        String codeurl = new String(url.getBytes("UTF-8"), "ISO-8859-1");

        // QRCode 색상값
        int qrcodeColor = 0xff080606;
        // QRCode 배경색상값
        int backgroundColor = 0xFFFFFFFF;

        //QRCode 생성
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        BitMatrix bitMatrix = qrCodeWriter.encode(codeurl, BarcodeFormat.QR_CODE,200, 200);    // 200,200은 width, height

        MatrixToImageConfig matrixToImageConfig = new MatrixToImageConfig(qrcodeColor,backgroundColor);
        BufferedImage bufferedImage = MatrixToImageWriter.toBufferedImage(bitMatrix,matrixToImageConfig);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        ImageIO.write(bufferedImage, "png", baos);
        baos.flush();

        MultipartFile multipartFile = new MockMultipartFile("qrcode", "qrcode", "image/png", baos.toByteArray());
        String qrcodeUrl = s3Uploder.upload(multipartFile, "qr");

        order.updateQrcodeUrl(qrcodeUrl);
    }
}
