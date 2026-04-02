package com.example.demo.service;

import com.example.demo.model.Booking;
import com.example.demo.repository.BookingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

@Service
public class BookingService {

    @Autowired
    private BookingRepository repository;

    private List<String> logs = new ArrayList<>();
    private String[] otherServers = {
            "https://dien-toan-lan-hai.onrender.com",
            "https://dientoanck.onrender.com"
    };

    private ConcurrentHashMap<String, Boolean> serverStatus = new ConcurrentHashMap<>();
    private int clock = 0;

    public BookingService() {
        for (String url : otherServers) {
            serverStatus.put(url, true);
        }
    }

    private synchronized int tick() { return ++clock; }
    private synchronized void updateClock(int received) { clock = Math.max(clock, received) + 1; }

    private String log(String type, String message) {
        String time = LocalTime.now().withNano(0).toString();
        return "[" + time + "] [L=" + clock + "] [" + type + "] " + message;
    }

    public void book(Booking b, String serverId) {
        tick();
        logs.add(log("CLIENT", "Nhận request đặt phòng: " + b.getName()));
        b.setLamportTime(clock);

        // Chạy bất đồng bộ bằng CompletableFuture
        CompletableFuture.runAsync(() -> {
            RestTemplate restTemplate = createRestTemplate();
            List<String> okServers = new ArrayList<>();

            // ===== PHASE 1: PREPARE (Có Retry) =====
            for (String url : otherServers) {
                boolean success = false;
                int retryCount = 0;
                int maxRetries = 2; // Thử lại tối đa 2 lần

                while (!success && retryCount <= maxRetries) {
                    try {
                        tick();
                        if(retryCount > 0) logs.add(log("2PC", "Thử lại lần " + retryCount + " tới " + url));
                        else logs.add(log("2PC", "Gửi PREPARE tới " + url));

                        Boolean res = restTemplate.postForObject(url + "/api/prepare", b, Boolean.class);

                        if (Boolean.TRUE.equals(res)) {
                            okServers.add(url);
                            serverStatus.put(url, true);
                            logs.add(log("2PC", "VOTE OK từ " + url));
                            success = true;
                        } else {
                            logs.add(log("2PC", "VOTE FAIL từ " + url));
                            success = true; // Nhận phản hồi rồi thì không retry
                        }
                    } catch (Exception e) {
                        retryCount++;
                        if (retryCount > maxRetries) {
                            serverStatus.put(url, false);
                            logs.add(log("ERROR", "Server thực sự DOWN sau " + maxRetries + " lần thử: " + url));
                        }
                    }
                }
            }

            // ===== QUYẾT ĐỊNH THEO QUORUM (ĐA SỐ) =====
            // Tính toán: Chỉ cần >= 50% số server khác OK (hoặc tùy bạn chỉnh ngưỡng)
            int threshold = ((otherServers.length / 2)+1); 

            if (okServers.size() >= threshold) {
                tick();
                logs.add(log("2PC", "QUORUM ĐẠT (" + okServers.size() + "/" + otherServers.length + ") → COMMIT"));

                // Commit local
                repository.save(b);
                logs.add(log("DATABASE", "Đã COMMIT local"));

                // Gửi Commit tới các server còn sống
                for (String url : okServers) {
                    try {
                        restTemplate.postForObject(url + "/api/commit", b, String.class);
                    } catch (Exception e) {
                        logs.add(log("ERROR", "Không thể gửi COMMIT tới " + url + " (vẫn lưu local)"));
                    }
                }
            } else {
                tick();
                logs.add(log("2PC", "ABORT: Không đủ số lượng server tối thiểu (Quorum fail)"));
            }
        });
    }

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000); // Tăng lên 3s cho Render
        factory.setReadTimeout(3000);
        return new RestTemplate(factory);
    }

    // Các hàm Prepare/Commit/GetLogs giữ nguyên như cũ
    public boolean prepare(Booking b) {
        updateClock(b.getLamportTime());
        logs.add(log("2PC", "Nhận PREPARE"));
        return true;
    }

    public void commit(Booking b) {
        updateClock(b.getLamportTime());
        logs.add(log("2PC", "Nhận COMMIT"));
        repository.save(b);
        tick();
        logs.add(log("DATABASE", "Đã COMMIT vào DB"));
    }

    public List<String> getLogs() { return logs; }
    public ConcurrentHashMap<String, Boolean> getServerStatus() { return serverStatus; }
}