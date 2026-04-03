package com.example.demo.service;

import com.example.demo.model.Booking;
import com.example.demo.repository.BookingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;

@Service
public class BookingService {

    @Autowired
    private BookingRepository repository;

    private List<String> logs = Collections.synchronizedList(new ArrayList<>());

    private String[] otherServers = {
            "https://dien-toan-lan-hai.onrender.com",
            "https://dientoanck.onrender.com",
            "https://demo2-75m2.onrender.com"
    };

    private ConcurrentHashMap<String, Boolean> serverStatus = new ConcurrentHashMap<>();

    // 🔥 Lưu các commit bị fail để recovery
    private ConcurrentHashMap<String, List<Booking>> pendingCommits = new ConcurrentHashMap<>();

    private ExecutorService executor = Executors.newFixedThreadPool(5);

    private int clock = 0;

    public BookingService() {
        for (String url : otherServers) {
            serverStatus.put(url, true);
        }
    }

    // ================= LAMPORT =================
    private synchronized int tick() { return ++clock; }

    private synchronized void updateClock(int received) {
        clock = Math.max(clock, received) + 1;
    }

    private String log(String type, String message) {
        String time = LocalTime.now().withNano(0).toString();
        return "[" + time + "] [L=" + clock + "] [" + type + "] " + message;
    }

    // ================= MAIN =================
    public void book(Booking b, String serverId) {
        tick();
        logs.add(log("CLIENT", "Nhận request: " + b.getName()));
        b.setLamportTime(clock);

        CompletableFuture.runAsync(() -> {

            RestTemplate rt = createRestTemplate();
            List<String> okServers = Collections.synchronizedList(new ArrayList<>());

            // ===== PHASE 1: PREPARE =====
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (String url : otherServers) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        tick();
                        logs.add(log("4PC", "PREPARE → " + url));

                        Boolean res = rt.postForObject(url + "/api/prepare", b, Boolean.class);

                        if (Boolean.TRUE.equals(res)) {
                            okServers.add(url);
                            logs.add(log("4PC", "VOTE OK từ " + url));
                        }

                    } catch (Exception e) {
                        logs.add(log("ERROR", "PREPARE fail: " + url));
                    }
                }, executor));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            int threshold = (otherServers.length / 2) + 1;

            if (okServers.size() < threshold) {
                logs.add(log("4PC", "ABORT: Không đủ quorum"));
                sendAbort(rt, okServers, b);
                return;
            }

            logs.add(log("4PC", "QUORUM OK → PRE-COMMIT"));

            // ===== PHASE 2: PRE-COMMIT =====
            List<String> preAckServers = Collections.synchronizedList(new ArrayList<>());
            List<CompletableFuture<Void>> preFutures = new ArrayList<>();

            for (String url : okServers) {
                preFutures.add(CompletableFuture.runAsync(() -> {
                    try {
                        tick();
                        logs.add(log("4PC", "PRE-COMMIT → " + url));

                        String res = rt.postForObject(url + "/api/pre-commit", b, String.class);

                        if ("PRE_ACK".equals(res)) {
                            preAckServers.add(url);
                            logs.add(log("4PC", "PRE_ACK từ " + url));
                        }

                    } catch (Exception e) {
                        logs.add(log("ERROR", "PRE-COMMIT fail: " + url));
                    }
                }, executor));
            }

            CompletableFuture.allOf(preFutures.toArray(new CompletableFuture[0])).join();

            // ===== RETRY =====
            if (preAckServers.size() < threshold) {
                logs.add(log("4PC", "Retry PRE-COMMIT..."));
                sleep(2000);

                List<CompletableFuture<Void>> retryFutures = new ArrayList<>();

                for (String url : okServers) {
                    if (!preAckServers.contains(url)) {
                        retryFutures.add(CompletableFuture.runAsync(() -> {
                            try {
                                String res = rt.postForObject(url + "/api/pre-commit", b, String.class);

                                if ("PRE_ACK".equals(res)) {
                                    preAckServers.add(url);
                                    logs.add(log("4PC", "PRE_ACK (retry) từ " + url));
                                }

                            } catch (Exception e) {
                                logs.add(log("ERROR", "Retry fail: " + url));
                            }
                        }, executor));
                    }
                }

                CompletableFuture.allOf(retryFutures.toArray(new CompletableFuture[0])).join();
            }

            // ===== DECISION =====
            if (preAckServers.size() >= threshold) {

                tick();
                logs.add(log("4PC", "ĐỦ PRE_ACK → COMMIT"));

                // commit local
                repository.save(b);
                logs.add(log("DATABASE", "COMMIT local OK"));

                // ===== PHASE 3: COMMIT =====
                for (String url : preAckServers) {
                    try {
                        rt.postForObject(url + "/api/commit", b, String.class);
                        logs.add(log("4PC", "COMMIT → " + url));
                    } catch (Exception e) {
                        logs.add(log("ERROR", "COMMIT fail: " + url));

                        // 🔥 lưu lại để recovery
                        pendingCommits
                                .computeIfAbsent(url, k -> Collections.synchronizedList(new ArrayList<>()))
                                .add(b);
                    }
                }

            } else {
                logs.add(log("4PC", "TIMEOUT → ABORT"));
                sendAbort(rt, okServers, b);
            }

        }, executor);
    }

    // ================= RECOVERY AUTO =================
    @Scheduled(fixedDelay = 5000)
    public void autoRecovery() {
        RestTemplate rt = createRestTemplate();

        for (String url : pendingCommits.keySet()) {

            List<Booking> list = pendingCommits.get(url);
            Iterator<Booking> iterator = list.iterator();

            while (iterator.hasNext()) {
                Booking b = iterator.next();

                try {
                    rt.postForObject(url + "/api/commit", b, String.class);
                    logs.add(log("RECOVERY", "Đồng bộ lại thành công → " + url));

                    iterator.remove();

                } catch (Exception e) {
                    logs.add(log("RECOVERY", "Server chưa sống → " + url));
                }
            }
        }
    }

    private void sendAbort(RestTemplate rt, List<String> servers, Booking b) {
        for (String url : servers) {
            try {
                rt.postForObject(url + "/api/abort", b, String.class);
            } catch (Exception ignored) {}
        }
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (Exception ignored) {}
    }

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(3000);
        return new RestTemplate(f);
    }

    // ================= PARTICIPANT =================
    public boolean prepare(Booking b) {
        updateClock(b.getLamportTime());
        logs.add(log("4PC", "Nhận PREPARE"));
        return true;
    }

    public String preCommit(Booking b) {
        updateClock(b.getLamportTime());
        logs.add(log("4PC", "Nhận PRE-COMMIT → log tạm"));
        return "PRE_ACK";
    }

    public void commit(Booking b) {
        updateClock(b.getLamportTime());
        repository.save(b);
        logs.add(log("4PC", "COMMIT thành công"));
    }

    // ================= GET =================
    public List<String> getLogs() { return logs; }
    public ConcurrentHashMap<String, Boolean> getServerStatus() { return serverStatus; }
}