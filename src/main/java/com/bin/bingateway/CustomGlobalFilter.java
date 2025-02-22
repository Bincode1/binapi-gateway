package com.bin.bingateway;

import com.alibaba.nacos.common.utils.StringUtils;
import com.bin.binapiclientsdk.uitls.SignUtils;
import com.bin.bincommon.model.InterfaceInfo;
import com.bin.bincommon.model.User;
import com.bin.bincommon.service.InnerInterfaceInfoService;
import com.bin.bincommon.service.InnerUserInterfaceInfoService;
import com.bin.bincommon.service.InnerUserService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 全局过滤
 */
@Component
public class CustomGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CustomGlobalFilter.class);

    private static final List<String> IP_WHITE_LIST = List.of("127.0.0.1");
    @DubboReference
    InnerUserService innerUserService;

    @DubboReference
    InnerInterfaceInfoService innerInterfaceInfoService;

    @DubboReference
    InnerUserInterfaceInfoService innerUserInterfaceInfoService;

    private static final String INTERFACEC_HOST = "https://api.moonshot.cn/v1";
    private static final String API_URL = "https://api.hunyuan.cloud.tencent.com";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponse response = exchange.getResponse();
        System.out.println(System.currentTimeMillis() + "-------------------我的请求被网关拦截了，开始处理");
        log.info("custom global filter");
        System.out.println("custom global filter");
//        1. 请求日志
        ServerHttpRequest request = exchange.getRequest();
        HttpHeaders headers = request.getHeaders();
        log.info("请求唯一标识 " + request.getId());
        String path = API_URL + request.getPath().value();
        String method = request.getMethod().toString();
        log.info("请求参数 ");
        log.info("请求来源地址" + request.getLocalAddress().getHostString());
        log.info("请求来源地址" + request.getRemoteAddress().getAddress().getHostAddress());

        String xForwardedFor = headers.getFirst("X-Forwarded-For");
        log.info("xForwardedFor 地址" + xForwardedFor);
        String sourceAddress = null;
        if (xForwardedFor == null || xForwardedFor.isEmpty()) {
            // 如果没有X-Forwarded-For头，直接返回远程地址
            sourceAddress = request.getRemoteAddress() != null
                    ? request.getRemoteAddress().getAddress().getHostAddress()
                    : null;
        }
//        2. 黑白名单
        if (!IP_WHITE_LIST.contains(sourceAddress)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return response.setComplete();
        }
//        3. 用户鉴权（判断ak， sk是否合法）
        String accessKey = headers.getFirst("accessKey");
        String nonce = headers.getFirst("nonce");
        String timestamp = headers.getFirst("timestamp");
        String sign = headers.getFirst("sign");

// 检查必要参数是否存在
        if (!validateRequiredParams(accessKey, nonce, timestamp, sign)) {
            return handlerInvalidParams(response, "缺少必要请求头参数");
        }
        // 去数据库中查是否已分配给用户
        if (Long.parseLong(nonce) > 10000) {
            return handlerNoAuth(response);
        }
        // 时间和当前时间不能超过五分钟
        assert timestamp != null;
        if (System.currentTimeMillis() / 1000 - Long.parseLong(timestamp) > 60 * 5) {
            return handlerNoAuth(response);
        }
        // todo 实际情况也是secretKey是从数据库中查询出来的
        User invokeUser = null;
        try {
            invokeUser = innerUserService.getInvokeUser(accessKey);
        } catch (Exception e) {
            log.error("getInvokeUser error", e);
        }
        if (invokeUser == null) {
            return handlerNoAuth(response);
        }

        String secretkey = invokeUser.getSecretkey();
        String serverSign = SignUtils.generateSignature(accessKey, secretkey, nonce, timestamp);
        if (sign == null || !sign.equals(serverSign)) {
            return handlerNoAuth(response);
        }

        // 4. 判断请求的模拟接口是否存在
        InterfaceInfo interfaceInfo = null;
        try {
            interfaceInfo = innerInterfaceInfoService.getInterfaceInfo(path, method);
        } catch (Exception e) {
            log.error("getInterfaceInfo error", e);
        }
        if (interfaceInfo == null) {
            return handlerNoAuth(response);
        }
        System.out.println("------------------网关层面的校验接口-------------:" + System.currentTimeMillis());
        // 5. 请求转发，调用模拟接口 + 响应日志
        return handleResponse(exchange, chain, interfaceInfo.getId(), invokeUser.getId());

    }


    /**
     * 处理响应
     *
     * @param exchange
     * @param chain
     * @return
     */
    public Mono<Void> handleResponse(ServerWebExchange exchange, GatewayFilterChain chain, long interfaceInfoId, long userId) {
        try {
            ServerHttpResponse originalResponse = exchange.getResponse();
            DataBufferFactory bufferFactory = originalResponse.bufferFactory();
            HttpStatusCode statusCode = originalResponse.getStatusCode();
            if (statusCode == HttpStatus.OK) {
                // 装饰 ，增强能力
                ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
                    @Override
                    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                        log.info("body instanceof Flux: {}", (body instanceof Flux));
                        if (body instanceof Flux) {
                            Flux<? extends DataBuffer> fluxBody = Flux.from(body);
                            // 往返回值里写数据
                            // 拼接字符串
                            return super.writeWith(
                                    fluxBody.map(dataBuffer -> {
                                        // 7.调用成功，接口调用次数+1   invokeCount
                                        try {
                                            innerUserInterfaceInfoService.invokeCount(interfaceInfoId, userId);
                                        } catch (Exception e) {
                                            log.error("invokeCount error", e);
                                        }
                                        byte[] content = new byte[dataBuffer.readableByteCount()];
                                        dataBuffer.read(content);
                                        DataBufferUtils.release(dataBuffer);
                                        // 构建日志
                                        StringBuilder sb2 = new StringBuilder(200);
                                        List<Object> rspArgs = new ArrayList<>();
                                        rspArgs.add(originalResponse.getStatusCode());

                                        String data = new String(content, StandardCharsets.UTF_8);

                                        sb2.append(data);
                                        // 打印日志
                                        log.info("响应结果：" + data);
                                        return bufferFactory.wrap(content);
                                    })
                            );
                        } else {
                            handlerInvokeError(originalResponse);
                            log.error("<--- {} 响应code异常", getStatusCode());
                        }
                        return super.writeWith(body);
                    }
                };
                // 设置response 对象装饰过的
                return chain.filter(exchange.mutate().response(decoratedResponse).build());
            }
            return chain.filter(exchange);
        } catch (Exception e) {
            log.error("网关处理响应异常.\n", e);
            return chain.filter(exchange);
        }
    }



    @Override
    public int getOrder() {
        return -1;
    }

    // 参数存在性校验方法
    private boolean validateRequiredParams(String... params) {
        for (String param : params) {
            if (!StringUtils.hasText(param)) {
                return false;
            }
        }
        return true;
    }
    // 统一错误处理方法
    private Mono<Void> handlerInvalidParams(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.BAD_REQUEST);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String jsonBody = String.format("{\"code\":400, \"msg\":\"%s\"}", message);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(jsonBody.getBytes())));
    }
    public Mono<Void> handlerNoAuth(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return response.setComplete();
    }

    public Mono<Void> handlerInvokeError(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        return response.setComplete();
    }
}