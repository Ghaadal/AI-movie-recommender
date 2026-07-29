package com.moodvie;

import org.json.*;
import java.sql.*;
import java.util.*;

public class MovieService {

    // Database connection settings
    static final String DB_URL;
    static final String DB_USER;
    static final String DB_PASSWORD;
    static final String TMDB_API_KEY;
    static final String GROQ_API_KEY;

    static {
    // Try environment variables first, then properties file
    String tmdb = System.getenv("tmdb.api.key");
    String groq = System.getenv("groq.api.key");
    String dbUrl = System.getenv("db.url");
    String dbUser = System.getenv("db.username");
    String dbPass = System.getenv("db.password");

    if (tmdb == null) {
        try {
            java.util.Properties props = new java.util.Properties();
            props.load(MovieService.class.getClassLoader().getResourceAsStream("application.properties"));
            tmdb = props.getProperty("tmdb.api.key");
            groq = props.getProperty("groq.api.key");
            dbUrl = props.getProperty("db.url");
            dbUser = props.getProperty("db.username");
            dbPass = props.getProperty("db.password");
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config: " + e.getMessage());
        }
    }

    TMDB_API_KEY = tmdb;
    GROQ_API_KEY = groq;
    DB_URL = dbUrl;
    DB_USER = dbUser;
    DB_PASSWORD = dbPass;
}

    // Connect to MySQL database
    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    // Get existing user or create new one
    public static int getOrCreateUser(String username) {
        try (Connection conn = connect()) {
            String checkSql = "SELECT id FROM users WHERE username = ?";
            PreparedStatement check = conn.prepareStatement(checkSql);
            check.setString(1, username);
            ResultSet rs = check.executeQuery();
            if (rs.next()) return rs.getInt("id");

            String sql = "INSERT INTO users (username) VALUES (?)";
            PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            pstmt.setString(1, username);
            pstmt.executeUpdate();
            ResultSet keys = pstmt.getGeneratedKeys();
            if (keys.next()) return keys.getInt(1);
        } catch (SQLException e) {
            System.out.println("DB Error: " + e.getMessage());
        }
        return -1;
    }

    // Get trending movies from TMDB sorted by rating
    public static List<Map<String, Object>> getTrending() {
        List<Map<String, Object>> movies = new ArrayList<>();
        try {
            String apiUrl = "https://api.themoviedb.org/3/trending/movie/week?api_key=" + TMDB_API_KEY;
            java.net.URL url = new java.net.URL(apiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");

            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);
            reader.close();

            JSONObject json = new JSONObject(response.toString());
            JSONArray results = json.getJSONArray("results");

            // Sort by rating
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < results.length(); i++) list.add(results.getJSONObject(i));
            list.sort((a, b) -> Double.compare(b.optDouble("vote_average", 0), a.optDouble("vote_average", 0)));

            for (int i = 0; i < Math.min(10, list.size()); i++) {
                JSONObject movie = list.get(i);
                Map<String, Object> m = new HashMap<>();
                m.put("title", movie.getString("title"));
                String releaseDate = movie.optString("release_date", "N/A");
                m.put("year", releaseDate.length() >= 4 ? releaseDate.substring(0, 4) : "N/A");
                m.put("rating", movie.optDouble("vote_average", 0.0));
                m.put("poster", "https://image.tmdb.org/t/p/w300" + movie.optString("poster_path", ""));
                m.put("overview", movie.optString("overview", ""));
                movies.add(m);
            }
        } catch (Exception e) {
            System.out.println("Trending Error: " + e.getMessage());
        }
        return movies;
    }

    // Use Groq AI to extract search keyword from user request
    public static String extractKeyword(String userRequest) {
        try {
            String prompt = "Extract ONE simple English search keyword from this movie request for TMDB. " +
                           "If actor or actress name mentioned, use their last name. " +
                           "If director name mentioned, use their last name. " +
                           "Return ONLY one word. User request: " + userRequest;

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
            conn.setRequestProperty("Authorization", "Bearer " + GROQ_API_KEY);
            conn.setDoOutput(true);
            conn.getOutputStream().write(requestJson.toString().getBytes("UTF-8"));

            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);
            reader.close();

            JSONObject json = new JSONObject(response.toString());
            return json.getJSONArray("choices").getJSONObject(0)
                       .getJSONObject("message").getString("content").trim();
        } catch (Exception e) {
            return userRequest;
        }
    }

    // Search movies from TMDB - checks XML cache first
    public static List<Map<String, Object>> fetchFromTMDB(String keyword) {
        List<Map<String, Object>> movies = new ArrayList<>();

        // Check XML cache first to avoid unnecessary API calls
        List<Map<String, Object>> cached = XmlCache.loadFromCache(keyword);
        if (!cached.isEmpty()) return cached;

        try {
            String encodedQuery = java.net.URLEncoder.encode(keyword, "UTF-8");
            String apiUrl = "https://api.themoviedb.org/3/search/movie?api_key="
                          + TMDB_API_KEY + "&query=" + encodedQuery + "&language=en-US";

            java.net.URL url = new java.net.URL(apiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");

            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);
            reader.close();

            JSONObject json = new JSONObject(response.toString());
            JSONArray results = json.getJSONArray("results");

            // Filter movies with enough votes and overview
            for (int i = 0; i < results.length(); i++) {
                JSONObject movie = results.getJSONObject(i);
                if (movie.optInt("vote_count", 0) > 10 &&
                    !movie.optString("overview", "").isEmpty()) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("title", movie.getString("title"));
                    String releaseDate = movie.optString("release_date", "N/A");
                    m.put("year", releaseDate.length() >= 4 ? releaseDate.substring(0, 4) : "N/A");
                    m.put("rating", movie.optDouble("vote_average", 0.0));
                    m.put("poster", "https://image.tmdb.org/t/p/w300" + movie.optString("poster_path", ""));
                    m.put("overview", movie.optString("overview", ""));
                    movies.add(m);
                }
            }

            // Sort by rating
            movies.sort((a, b) -> Double.compare(
                (double) b.get("rating"), (double) a.get("rating")));

            // Save results to XML cache
            XmlCache.saveToCache(keyword, movies);

        } catch (Exception e) {
            System.out.println("TMDB Error: " + e.getMessage());
        }
        return movies;
    }

    // Search movies with AI - extracts keyword then picks best matches
    public static Map<String, Object> searchWithAI(String userRequest) {
        Map<String, Object> result = new HashMap<>();
        try {
            // Step 1: Extract keyword using AI
            String keyword = extractKeyword(userRequest);

            // Step 2: Fetch movies from TMDB
            List<Map<String, Object>> movies = fetchFromTMDB(keyword);

            if (movies.isEmpty()) {
                result.put("movies", new ArrayList<>());
                result.put("keyword", keyword);
                return result;
            }

            // Step 3: Build movie list with stories for AI
            StringBuilder movieList = new StringBuilder();
            for (int i = 0; i < Math.min(15, movies.size()); i++) {
                Map<String, Object> movie = movies.get(i);
                movieList.append((i + 1)).append(". ")
                         .append(movie.get("title")).append(" (").append(movie.get("year")).append(")")
                         .append(" Rating: ").append(movie.get("rating"))
                         .append(" | Story: ").append(movie.get("overview")).append("\n");
            }

            // Step 4: Ask AI to pick best matches
            String prompt = "The user wants: " + userRequest + "\n\n" +
                           "Here are movies with their stories:\n" + movieList +
                           "\nPick the TOP 5 that best match. Return a JSON array with objects containing: " +
                           "title, year, rating (number), reason (one sentence why it matches). " +
                           "Return ONLY the JSON array, nothing else.";

            JSONObject message = new JSONObject();
            message.put("role", "user");
            message.put("content", prompt);

            JSONArray messages = new JSONArray();
            messages.put(message);

            JSONObject requestJson = new JSONObject();
            requestJson.put("model", "llama-3.3-70b-versatile");
            requestJson.put("max_tokens", 1000);
            requestJson.put("messages", messages);

            java.net.URL url = new java.net.URL("https://api.groq.com/openai/v1/chat/completions");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + GROQ_API_KEY);
            conn.setDoOutput(true);
            conn.getOutputStream().write(requestJson.toString().getBytes("UTF-8"));

            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);
            reader.close();

            JSONObject json = new JSONObject(response.toString());
            String content = json.getJSONArray("choices").getJSONObject(0)
                               .getJSONObject("message").getString("content").trim();

            // Parse AI response JSON
            int start = content.indexOf("[");
            int end = content.lastIndexOf("]") + 1;
            if (start >= 0 && end > start) {
                JSONArray aiMovies = new JSONArray(content.substring(start, end));
                List<Map<String, Object>> finalMovies = new ArrayList<>();

                for (int i = 0; i < aiMovies.length(); i++) {
                    JSONObject m = aiMovies.getJSONObject(i);
                    String aiTitle = m.optString("title", "");

                    // Find poster from original list
                    String poster = "";
                    double realRating = 0.0;
                    for (Map<String, Object> orig : movies) {
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
                result.put("movies", finalMovies);
            } else {
                result.put("movies", movies.subList(0, Math.min(5, movies.size())));
            }
            result.put("keyword", keyword);

        } catch (Exception e) {
            System.out.println("AI Error: " + e.getMessage());
            result.put("movies", new ArrayList<>());
            result.put("error", e.getMessage());
        }
        return result;
    }

    // Save movie to user's saved list
    public static void saveMovie(String username, String title, double rating, String poster) {
        try (Connection conn = connect()) {
            int userId = getOrCreateUser(username);

            // Check if already saved
            String checkSaved = "SELECT r.id FROM user_ratings r JOIN movies m ON r.movie_id = m.id " +
                               "WHERE r.user_id = ? AND m.title = ? AND r.review = 'saved'";
            PreparedStatement checkS = conn.prepareStatement(checkSaved);
            checkS.setInt(1, userId);
            checkS.setString(2, title);
            ResultSet rsS = checkS.executeQuery();
            if (rsS.next()) {
                System.out.println("Already saved: " + title);
              throw new RuntimeException("Already saved");
                
            }

            // Check if movie exists in DB
            String checkSql = "SELECT id FROM movies WHERE title = ?";
            PreparedStatement check = conn.prepareStatement(checkSql);
            check.setString(1, title);
            ResultSet rs = check.executeQuery();

            int movieId;
            if (rs.next()) {
                movieId = rs.getInt("id");
                // Update poster if empty
                String updatePoster = "UPDATE movies SET poster = ? WHERE id = ? AND (poster IS NULL OR poster = '')";
                PreparedStatement up = conn.prepareStatement(updatePoster);
                up.setString(1, poster);
                up.setInt(2, movieId);
                up.executeUpdate();
            } else {
                // Add new movie to DB
                String insertSql = "INSERT INTO movies (title, genre, mood, rating, poster) VALUES (?, 'Unknown', 'Unknown', ?, ?)";
                PreparedStatement insert = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS);
                insert.setString(1, title);
                insert.setDouble(2, rating);
                insert.setString(3, poster);
                insert.executeUpdate();
                ResultSet keys = insert.getGeneratedKeys();
                keys.next();
                movieId = keys.getInt(1);
            }

            // Save to user_ratings
            String saveSql = "INSERT INTO user_ratings (user_id, movie_id, rating, review, poster) VALUES (?, ?, ?, 'saved', ?)";
            PreparedStatement save = conn.prepareStatement(saveSql);
            save.setInt(1, userId);
            save.setInt(2, movieId);
            save.setDouble(3, rating);
            save.setString(4, poster);
            save.executeUpdate();
            System.out.println("Saved: " + title);

        } catch (SQLException e) {
            System.out.println("Save Error: " + e.getMessage());
        }
    }

    // Remove movie from user's saved list
    public static void removeMovie(String username, String title) {
        try (Connection conn = connect()) {
            int userId = getOrCreateUser(username);
            String sql = "DELETE r FROM user_ratings r JOIN movies m ON r.movie_id = m.id " +
                        "WHERE r.user_id = ? AND m.title = ? AND r.review = 'saved'";
            PreparedStatement pstmt = conn.prepareStatement(sql);
            pstmt.setInt(1, userId);
            pstmt.setString(2, title);
            pstmt.executeUpdate();
            System.out.println("Removed: " + title);
        } catch (SQLException e) {
            System.out.println("Remove Error: " + e.getMessage());
        }
    }

    // Get all saved movies for a user
    public static List<Map<String, Object>> getSavedMovies(String username) {
        List<Map<String, Object>> movies = new ArrayList<>();
        try (Connection conn = connect()) {
            int userId = getOrCreateUser(username);
            String sql = "SELECT m.title, r.rating, COALESCE(r.poster, m.poster, '') as poster " +
                        "FROM user_ratings r JOIN movies m ON r.movie_id = m.id " +
                        "WHERE r.user_id = ? AND r.review = 'saved' ORDER BY r.rating DESC";
            PreparedStatement pstmt = conn.prepareStatement(sql);
            pstmt.setInt(1, userId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new HashMap<>();
                m.put("title", rs.getString("title"));
                m.put("rating", rs.getDouble("rating"));
                m.put("poster", rs.getString("poster"));
                movies.add(m);
            }
        } catch (SQLException e) {
            System.out.println("Error: " + e.getMessage());
        }
        return movies;
    }
}