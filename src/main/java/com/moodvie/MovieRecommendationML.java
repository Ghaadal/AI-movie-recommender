package com.moodvie;

import org.json.*;
import java.sql.*;
import java.util.*;

public class MovieRecommendationML {


    // Get user's saved movies with their overviews
    public static List<Map<String, Object>> getUserProfile(String username) {
        List<Map<String, Object>> savedMovies = new ArrayList<>();
        try (Connection conn = MovieService.connect()) {
            int userId = MovieService.getOrCreateUser(username);
            String sql = "SELECT m.title, m.rating, m.poster FROM user_ratings r " +
                        "JOIN movies m ON r.movie_id = m.id " +
                        "WHERE r.user_id = ? AND r.review = 'saved' ORDER BY r.rating DESC";
            PreparedStatement pstmt = conn.prepareStatement(sql);
            pstmt.setInt(1, userId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new HashMap<>();
                m.put("title", rs.getString("title"));
                m.put("rating", rs.getDouble("rating"));
                m.put("poster", rs.getString("poster"));
                savedMovies.add(m);
            }
        } catch (SQLException e) {
            System.out.println("ML Error: " + e.getMessage());
        }
        return savedMovies;
    }

    // Use AI to analyze user taste and recommend movies
    public static List<Map<String, Object>> getPersonalizedRecommendations(String username) {
        List<Map<String, Object>> savedMovies = getUserProfile(username);

        if (savedMovies.isEmpty()) return new ArrayList<>();

        try {
            // Build user taste profile
            StringBuilder savedList = new StringBuilder();
            for (Map<String, Object> movie : savedMovies) {
                savedList.append("- ").append(movie.get("title")).append("\n");
            }

            // Ask AI to find a keyword based on user taste
            String prompt = "Based on these movies the user saved: \n" + savedList +
                           "\nAnalyze what genres and themes they like. " +
                           "Return ONE search keyword that would find similar movies on TMDB. " +
                           "Return ONLY one word.";

            JSONObject message = new JSONObject();
            message.put("role", "user");
            message.put("content", prompt);

            JSONArray messages = new JSONArray();
            messages.put(message);

            JSONObject requestJson = new JSONObject();
            requestJson.put("model", "llama-3.3-70b-versatile");
            requestJson.put("max_tokens", 50);
            requestJson.put("messages", messages);

            java.net.URL url = new java.net.URL("https://api.groq.com/openai/v1/chat/completions");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + MovieService.GROQ_API_KEY);
            conn.setDoOutput(true);
            conn.getOutputStream().write(requestJson.toString().getBytes("UTF-8"));

            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);
            reader.close();

            JSONObject json = new JSONObject(response.toString());
            String keyword = json.getJSONArray("choices").getJSONObject(0)
                               .getJSONObject("message").getString("content").trim();

            System.out.println("ML keyword: " + keyword);

            // Fetch movies based on keyword
            List<Map<String, Object>> candidates = MovieService.fetchFromTMDB(keyword);

            // Filter out already saved movies
            Set<String> savedTitles = new HashSet<>();
            for (Map<String, Object> m : savedMovies) savedTitles.add(m.get("title").toString().toLowerCase());

            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> m : candidates) {
                if (!savedTitles.contains(m.get("title").toString().toLowerCase())) {
                    filtered.add(m);
                }
            }

            // Ask AI to pick best matches
            if (filtered.isEmpty()) return new ArrayList<>();

            StringBuilder movieList = new StringBuilder();
            for (int i = 0; i < Math.min(15, filtered.size()); i++) {
                Map<String, Object> movie = filtered.get(i);
                movieList.append((i + 1)).append(". ")
                         .append(movie.get("title")).append(" (").append(movie.get("year")).append(")")
                         .append(" Rating: ").append(movie.get("rating"))
                         .append(" | Story: ").append(movie.get("overview")).append("\n");
            }

            String prompt2 = "The user likes these movies:\n" + savedList +
                            "\nFrom this list, pick TOP 5 movies they would enjoy:\n" + movieList +
                            "\nReturn a JSON array with: title, year, rating (number), reason. " +
                            "Return ONLY the JSON array.";

            JSONObject message2 = new JSONObject();
            message2.put("role", "user");
            message2.put("content", prompt2);

            JSONArray messages2 = new JSONArray();
            messages2.put(message2);

            JSONObject requestJson2 = new JSONObject();
            requestJson2.put("model", "llama-3.3-70b-versatile");
            requestJson2.put("max_tokens", 1000);
            requestJson2.put("messages", messages2);

            java.net.URL url2 = new java.net.URL("https://api.groq.com/openai/v1/chat/completions");
            java.net.HttpURLConnection conn2 = (java.net.HttpURLConnection) url2.openConnection();
            conn2.setRequestMethod("POST");
            conn2.setRequestProperty("Content-Type", "application/json");
            conn2.setRequestProperty("Authorization", "Bearer " + MovieService.GROQ_API_KEY);
            conn2.setDoOutput(true);
            conn2.getOutputStream().write(requestJson2.toString().getBytes("UTF-8"));

            java.io.BufferedReader reader2 = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn2.getInputStream()));
            StringBuilder response2 = new StringBuilder();
            String line2;
            while ((line2 = reader2.readLine()) != null) response2.append(line2);
            reader2.close();

            JSONObject json2 = new JSONObject(response2.toString());
            String content = json2.getJSONArray("choices").getJSONObject(0)
                               .getJSONObject("message").getString("content").trim();

            int start = content.indexOf("[");
            int end = content.lastIndexOf("]") + 1;
            if (start >= 0 && end > start) {
                JSONArray aiMovies = new JSONArray(content.substring(start, end));
                List<Map<String, Object>> finalMovies = new ArrayList<>();

                for (int i = 0; i < aiMovies.length(); i++) {
                    JSONObject m = aiMovies.getJSONObject(i);
                    String aiTitle = m.optString("title", "");
                    String poster = "";
                    double realRating = 0.0;
                    for (Map<String, Object> orig : filtered) {
                        if (orig.get("title").toString().equalsIgnoreCase(aiTitle)) {
                            poster = orig.get("poster").toString();
                            realRating = (double) orig.get("rating");
                            break;
                        }
                    }
                    Map<String, Object> movie = new HashMap<>();
                    movie.put("title", aiTitle);
                    movie.put("year", m.optString("year", ""));
                    movie.put("rating", realRating > 0 ? realRating : m.optDouble("rating", 0.0));
                    movie.put("reason", m.optString("reason", ""));
                    movie.put("poster", poster);
                    finalMovies.add(movie);
                }
                return finalMovies;
            }

        } catch (Exception e) {
            System.out.println("ML Recommendation Error: " + e.getMessage());
        }
        return new ArrayList<>();
    }
}