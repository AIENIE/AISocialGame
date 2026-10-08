package com.aisocialgame.service;

import org.springframework.stereotype.Component;
import java.security.SecureRandom;

@Component
public class RoomCodeGenerator {
    private final SecureRandom random = new SecureRandom();
    public String next() { return String.valueOf(100000 + random.nextInt(900000)); }
}
