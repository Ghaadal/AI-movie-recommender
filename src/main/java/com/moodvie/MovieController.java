package com.moodvie;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;

@RestController
@CrossOrigin(origins = "*")
public class MovieController {

    // Home page check
    @GetMapping("/")
    public String home() {
        return "Moodvie API is running!";
    }

    // Get trending movies from TMDB
    @GetMapping("/trending")
    public ResponseEntity<List<Map<String, Object>>> getTrending() {
        return ResponseEntity.ok(MovieService.getTrending());
    }

    // Search movies using AI
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchMovies(@RequestParam String query) {
        return ResponseEntity.ok(MovieService.searchWithAI(query));
    }

    // Get user's saved movies
    @GetMapping("/saved")
    public ResponseEntity<List<Map<String, Object>>> getSaved(@RequestParam String username) {
        return ResponseEntity.ok(MovieService.getSavedMovies(username));
    }

    // Save a movie
   @PostMapping("/save")
public ResponseEntity<String> saveMovie(@RequestBody Map<String, Object> body) {
    String username = (String) body.get("username");
    String title = (String) body.get("title");
    double rating = Double.parseDouble(body.get("rating").toString());
    String poster = (String) body.getOrDefault("poster", "");
    try {
        MovieService.saveMovie(username, title, rating, poster);
        return ResponseEntity.ok("Saved!");
    } catch (Exception e) {
        return ResponseEntity.ok("Already saved");
    }
}

    // Remove a movie from saved
    @DeleteMapping("/saved")
    public ResponseEntity<String> removeMovie(@RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        String title = (String) body.get("title");
        MovieService.removeMovie(username, title);
        return ResponseEntity.ok("Removed!");
    }

    // Get AI personalized recommendations based on user taste
    @GetMapping("/foryou")
    public ResponseEntity<List<Map<String, Object>>> getForYou(@RequestParam String username) {
        return ResponseEntity.ok(MovieRecommendationML.getPersonalizedRecommendations(username));
    }
}