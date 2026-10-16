package com.turbolink.controller;

import com.turbolink.exception.ShortLinkException;
import com.turbolink.mq.AccessEventProducer;
import com.turbolink.sequence.SegmentIdGenerator;
import com.turbolink.service.ShortLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ShortLinkController {

    private final ShortLinkService shortLinkService;
    private final SegmentIdGenerator idGenerator;
    private final AccessEventProducer accessEventProducer;

    /** 生成短链（支持自定义短码） */
    @PostMapping("/api/shorten")
    public Map<String, String> shorten(@RequestBody CreateRequest req) {
        String longUrl = req.getLongUrl();
        if (longUrl == null || !longUrl.startsWith("http")) {
            throw new ShortLinkException(HttpStatus.BAD_REQUEST, "非法的长链接");
        }
        long id = idGenerator.nextId();
        String shortUrl = shortLinkService.create(id, longUrl, req.getCustomCode());
        return Map.of("shortUrl", shortUrl);
    }

    /** 跳转 */
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String fullUrl = shortLinkService.redirect(shortCode);
        if (fullUrl == null) {
            return ResponseEntity.notFound().build();
        }
        // 跳转成功才投递统计事件；异步发送，不阻塞 302 返回
        accessEventProducer.sendAccess(shortCode);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(fullUrl))
                .build();
    }
}
