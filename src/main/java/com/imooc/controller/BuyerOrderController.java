package com.imooc.controller;

import com.imooc.VO.ResultVO;
import com.imooc.converter.OrderForm2OrderDTOConverter;
import com.imooc.dto.OrderDTO;
import com.imooc.enums.ResultEnum;
import com.imooc.exception.SellException;
import com.imooc.form.OrderForm;
import com.imooc.service.BuyerService;
import com.imooc.service.OrderService;
import com.imooc.utils.ResultVOUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by SqMax on 2018/3/20.
 */
@RestController
@RequestMapping("/buyer/order")
@Slf4j
public class BuyerOrderController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private BuyerService buyerService;


    @PostMapping("/create")
    public ResultVO create(@Valid OrderForm orderForm, 
                          @RequestHeader("Idempotency-Key") String idempotencyKey) {
        // 1. 校验幂等键
        if(StringUtils.isEmpty(idempotencyKey)) {
            throw new SellException(ResultEnum.IDEMPOTENCY_KEY_MISSING);
        }
        
        // 2. Redis检查是否已处理
        String key = "order:idempotent:" + orderForm.getBuyerOpenid() + ":" + idempotencyKey;
        Boolean exists = redisTemplate.hasKey(key);
        if(exists) {
            // 返回之前的结果或错误
            return ResultVOUtil.error(ResultEnum.REPEAT_SUBMIT);
        }
        
        // 3. 设置短期锁（5分钟TTL）
        redisTemplate.opsForValue().set(key, "processing", 5, TimeUnit.MINUTES);
        
        try {
            // 4. 执行正常业务逻辑
            OrderDTO orderDTO = orderService.create(converter.convert(orderForm));
            
            // 5. 存储成功结果
            redisTemplate.opsForValue().set(key, JSONObject.toJSONString(orderDTO), 30, TimeUnit.MINUTES);
            return ResultVOUtil.success(orderDTO);
        } catch(Exception e) {
            // 6. 异常情况删除key，允许重试
            redisTemplate.delete(key);
            throw e;
        }
    }

    //订单列表
    @GetMapping("/list")
    public ResultVO<List<OrderDTO>> list(@RequestParam("openid") String openid,
                                         @RequestParam(value = "page",defaultValue = "0") Integer page,
                                         @RequestParam(value = "size", defaultValue = "10") Integer size){
        if(StringUtils.isEmpty(openid)){
            log.error("【查询订单列表】openid为空");
            throw new SellException(ResultEnum.PARAM_ERROR);
        }
        PageRequest request=new PageRequest(page,size);
        Page<OrderDTO> orderDTOPage=orderService.findList(openid,request);

        return ResultVOUtil.success(orderDTOPage.getContent());
//        return ResultVOUtil.success();
    }
    //订单详情
    @GetMapping("/detail")
    public ResultVO<OrderDTO> detail(@RequestParam("openid") String openid,
                                     @RequestParam("orderId") String orderId){

        OrderDTO orderDTO=buyerService.findOrderOne(openid,orderId);
        return ResultVOUtil.success(orderDTO);
    }

    //取消订单
    @PostMapping("/cancel")
    public ResultVO cancel(@RequestParam("openid") String openid,
                           @RequestParam("orderId") String orderId){
        buyerService.cancelOrder(openid,orderId);
        return ResultVOUtil.success();
    }

}
