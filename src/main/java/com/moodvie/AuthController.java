package com.moodvie;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.sql.*;
import java.util.*;

@RestController
@CrossOrigin(origins = "*")
public class AuthController {

    static BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    // Register new user
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String username = (String) body.get("username");
            String email = (String) body.get("email");
            String password = (String) body.get("password");

            // Validate input
            if (username == null || password == null || email == null) {
                result.put("success", false);
                result.put("message", "All fields are required");
                return ResponseEntity.badRequest().body(result);
            }

            try (Connection conn = MovieService.connect()) {
                // Check if username exists
                String checkSql = "SELECT id FROM users WHERE username = ?";
                PreparedStatement check = conn.prepareStatement(checkSql);
                check.setString(1, username);
                ResultSet rs = check.executeQuery();
                if (rs.next()) {
                    result.put("success", false);
                    result.put("message", "Username already exists");
                    return ResponseEntity.badRequest().body(result);
                }

                // Check if email exists
                String checkEmail = "SELECT id FROM users WHERE email = ?";
                PreparedStatement checkE = conn.prepareStatement(checkEmail);
                checkE.setString(1, email);
                ResultSet rsE = checkE.executeQuery();
                if (rsE.next()) {
                    result.put("success", false);
                    result.put("message", "Email already exists");
                    return ResponseEntity.badRequest().body(result);
                }

                // Hash password and save user
                String hashedPassword = encoder.encode(password);
                String sql = "INSERT INTO users (username, email, password) VALUES (?, ?, ?)";
                PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                pstmt.setString(1, username);
                pstmt.setString(2, email);
                pstmt.setString(3, hashedPassword);
                pstmt.executeUpdate();

                result.put("success", true);
                result.put("username", username);
                result.put("message", "Account created successfully");
                return ResponseEntity.ok(result);
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Error: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    // Login user
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String username = (String) body.get("username");
            String password = (String) body.get("password");

            try (Connection conn = MovieService.connect()) {
                String sql = "SELECT * FROM users WHERE username = ?";
                PreparedStatement pstmt = conn.prepareStatement(sql);
                pstmt.setString(1, username);
                ResultSet rs = pstmt.executeQuery();

                if (rs.next()) {
                    String hashedPassword = rs.getString("password");
                    // Check password
                    if (encoder.matches(password, hashedPassword)) {
                        result.put("success", true);
                        result.put("username", username);
                        result.put("message", "Login successful");
                        return ResponseEntity.ok(result);
                    } else {
                        result.put("success", false);
                        result.put("message", "Wrong password");
                        return ResponseEntity.badRequest().body(result);
                    }
                } else {
                    result.put("success", false);
                    result.put("message", "User not found");
                    return ResponseEntity.badRequest().body(result);
                }
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Error: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}