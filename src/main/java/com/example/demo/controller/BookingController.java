package com.example.demo.controller;

import com.example.demo.model.Booking;
import com.example.demo.service.BookingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class BookingController {

    @Autowired
    private BookingService service;

    @Value("${server.id}")
    private String serverId;

    // ✅ API tạo booking (CHÍNH)
    @PostMapping("/book")
    public String book(@RequestBody Booking b) {
        System.out.println("Nhận request /book: " + b.getName());
        service.book(b, serverId);
        return "Booking thành công!";
    }

    // ✅ API nhận replicate từ server khác
    @PostMapping("/replicate")
    public String replicate(@RequestBody Booking b) {
        System.out.println("Nhận replicate: " + b.getName());
        service.replicate(b, serverId);
        return "Replicated OK";
    }

    // ✅ API lấy log
    @GetMapping("/log")
    public List<String> logs() {
        System.out.println("API /log được gọi");
        return service.getLogs();
    }

    @PostMapping("/replicate-log")
    public String replicateLog(@RequestBody String log) {
        service.addLog(log);
        return "OK";
    }
}