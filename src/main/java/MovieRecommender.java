import java.sql.*;
import java.util.Scanner;

public class MovieRecommender {

    static final String URL = "jdbc:mysql://localhost:3306/movies_db";
    static final String USER = "root";
    static final String PASSWORD = System.getProperty("DB_PASS", "1971@");
    static final String TMDB_API_KEY = System.getProperty("TMDB_KEY", "YOUR_TMDB_KEY");
    static final String GROQ_API_KEY = System.getProperty("GROQ_KEY", "YOUR_GROQ_KEY");

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    public static int addUser(String username) {
        try (Connection conn = connect()) {
            String checkSql = "SELECT id FROM users WHERE username = ?";
            PreparedStatement check = conn.prepareStatement(checkSql);
            check.setString(1, username);
            ResultSet rs = check.executeQuery();
            if (rs.next()) {
                System.out.println("Welcome back, " + username + "!");
                return rs.getInt("id");
            }
            String sql = "INSERT INTO users (username) VALUES (?)";
            PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            pstmt.setString(1, username);
            pstmt.executeUpdate();
            ResultSet keys = pstmt.getGeneratedKeys();
            if (keys.next()) {
                System.out.println("Welcome, " + username + "!");
                return keys.getInt(1);
            }
        } catch (SQLException e) {
            System.out.println("Error: " + e.getMessage());
        }
        return -1;
    }

    public static void saveMovie(int userId, String title, double rating) {
        try (Connection conn = connect()) {
            // Check if already saved
            String checkSaved = "SELECT r.id FROM user_ratings r JOIN movies m ON r.movie_id = m.id " +
                               "WHERE r.user_id = ? AND m.title = ? AND r.review = 'saved'";
            PreparedStatement checkS = conn.prepareStatement(checkSaved);
            checkS.setInt(1, userId);
            checkS.setString(2, title);
            ResultSet rsS = checkS.executeQuery();
            if (rsS.next()) {
                System.out.println("Already saved: " + title);
                return;
            }

            // Check if movie exists
            String checkSql = "SELECT id FROM movies WHERE title = ?";
            PreparedStatement check = conn.prepareStatement(checkSql);
            check.setString(1, title);
            ResultSet rs = check.executeQuery();

            int movieId;
            if (rs.next()) {
                movieId = rs.getInt("id");
            } else {
                String insertSql = "INSERT INTO movies (title, genre, mood, rating) VALUES (?, 'Unknown', 'Unknown', ?)";
                PreparedStatement insert = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS);
                insert.setString(1, title);
                insert.setDouble(2, rating);
                insert.executeUpdate();
                ResultSet keys = insert.getGeneratedKeys();
                keys.next();
                movieId = keys.getInt(1);
            }

            String saveSql = "INSERT INTO user_ratings (user_id, movie_id, rating, review) VALUES (?, ?, ?, 'saved')";
            PreparedStatement save = conn.prepareStatement(saveSql);
            save.setInt(1, userId);
            save.setInt(2, movieId);
            save.setDouble(3, rating);
            save.executeUpdate();
            System.out.println("Movie saved: " + title);

        } catch (SQLException e) {
            System.out.println("Error saving: " + e.getMessage());
        }
    }

    public static void showSavedMovies(int userId) {
        String sql = "SELECT m.title, r.rating FROM user_ratings r " +
                     "JOIN movies m ON r.movie_id = m.id WHERE r.user_id = ? AND r.review = 'saved' " +
                     "ORDER BY r.rating DESC";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            ResultSet rs = pstmt.executeQuery();
            System.out.println("\n=== My Saved Movies ===");
            boolean found = false;
            int i = 1;
            while (rs.next()) {
                found = true;
                System.out.printf("%d. %s | Rating: %.1f%n",
                    i++, rs.getString("title"), rs.getDouble("rating"));
            }
            if (!found) System.out.println("No saved movies yet.");
        } catch (SQLException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    public static void showTrending() {
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

            org.json.JSONObject json = new org.json.JSONObject(response.toString());
            org.json.JSONArray results = json.getJSONArray("results");

            // Sort by rating
            java.util.List<org.json.JSONObject> movies = new java.util.ArrayList<>();
            for (int i = 0; i < results.length(); i++) {
                movies.add(results.getJSONObject(i));
            }
            movies.sort((a, b) -> Double.compare(
                b.optDouble("vote_average", 0),
                a.optDouble("vote_average", 0)
            ));

            System.out.println("\n=== Trending This Week ===");
            for (int i = 0; i < Math.min(5, movies.size()); i++) {
                org.json.JSONObject movie = movies.get(i);
                String releaseDate = movie.optString("release_date", "N/A");
                String year = releaseDate.length() >= 4 ? releaseDate.substring(0, 4) : "N/A";
                System.out.printf("%d. %s (%s) | Rating: %.1f%n",
                    i + 1, movie.getString("title"), year,
                    movie.optDouble("vote_average", 0.0));
            }
        } catch (Exception e) {
            System.out.println("Trending Error: " + e.getMessage());
        }
    }

    public static String extractKeyword(String userRequest) {
        try {
            String apiUrl = "https://api.groq.com/openai/v1/chat/completions";
            String prompt = "Extract ONE simple English search keyword from this movie request for TMDB. " +
                           "If director or actor name mentioned, use their last name. " +
                           "Return ONLY one word. User request: " + userRequest;

            org.json.JSONObject message = new org.json.JSONObject();
            message.put("role", "user");
            message.put("content", prompt);

            org.json.JSONArray messages = new org.json.JSONArray();
            messages.put(message);

            org.json.JSONObject requestJson = new org.json.JSONObject();
            requestJson.put("model", "llama-3.3-70b-versatile");
            requestJson.put("max_tokens", 50);
            requestJson.put("messages", messages);

            java.net.URL url = new java.net.URL(apiUrl);
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

            org.json.JSONObject json = new org.json.JSONObject(response.toString());
            return json.getJSONArray("choices").getJSONObject(0)
                       .getJSONObject("message").getString("content").trim();

        } catch (Exception e) {
            return userRequest;
        }
    }

    public static java.util.List<org.json.JSONObject> fetchMoviesFromTMDB(String keyword) {
        java.util.List<org.json.JSONObject> movies = new java.util.ArrayList<>();
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

            org.json.JSONObject json = new org.json.JSONObject(response.toString());
            org.json.JSONArray results = json.getJSONArray("results");

            for (int i = 0; i < results.length(); i++) {
                org.json.JSONObject movie = results.getJSONObject(i);
                if (movie.optInt("vote_count", 0) > 10 &&
                    !movie.optString("overview", "").isEmpty()) {
                    movies.add(movie);
                }
            }

            // Sort by rating
            movies.sort((a, b) -> Double.compare(
                b.optDouble("vote_average", 0),
                a.optDouble("vote_average", 0)
            ));

        } catch (Exception e) {
            System.out.println("TMDB Error: " + e.getMessage());
        }
        return movies;
    }

    public static java.util.List<org.json.JSONObject> aiPickBestMovies(String userRequest, java.util.List<org.json.JSONObject> movies) {
        try {
            if (movies.isEmpty()) {
                System.out.println("No movies found.");
                return new java.util.ArrayList<>();
            }

            StringBuilder movieList = new StringBuilder();
            for (int i = 0; i < Math.min(15, movies.size()); i++) {
                org.json.JSONObject movie = movies.get(i);
                String title = movie.getString("title");
                String overview = movie.optString("overview", "No description");
                double rating = movie.optDouble("vote_average", 0.0);
                String releaseDate = movie.optString("release_date", "N/A");
                String year = releaseDate.length() >= 4 ? releaseDate.substring(0, 4) : "N/A";
                movieList.append((i + 1)).append(". ").append(title)
                         .append(" (").append(year).append(") Rating: ").append(rating)
                         .append(" | Story: ").append(overview).append("\n");
            }

            String prompt = "The user wants: " + userRequest + "\n\n" +
                           "Here are movies with their stories:\n" + movieList +
                           "\nRead each story carefully and pick the TOP 5 that best match what the user wants. " +
                           "Sort them by rating (highest first). " +
                           "For each movie write: title, year, rating, and ONE sentence why it matches. " +
                           "Format exactly like this:\n" +
                           "1. [Title] ([Year]) | Rating: [X.X]\n   Why: [reason]\n" +
                           "Only include movies that truly match. No extra text.";

            String apiUrl = "https://api.groq.com/openai/v1/chat/completions";

            org.json.JSONObject message = new org.json.JSONObject();
            message.put("role", "user");
            message.put("content", prompt);

            org.json.JSONArray messages = new org.json.JSONArray();
            messages.put(message);

            org.json.JSONObject requestJson = new org.json.JSONObject();
            requestJson.put("model", "llama-3.3-70b-versatile");
            requestJson.put("max_tokens", 1000);
            requestJson.put("messages", messages);

            java.net.URL url = new java.net.URL(apiUrl);
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

            org.json.JSONObject json = new org.json.JSONObject(response.toString());
            String result = json.getJSONArray("choices").getJSONObject(0)
                               .getJSONObject("message").getString("content").trim();

            System.out.println("\n=== Top Movies for You ===");
            System.out.println(result);

            return movies;

        } catch (Exception e) {
            System.out.println("AI Error: " + e.getMessage());
            return new java.util.ArrayList<>();
        }
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("=== Moodvie - AI Movie Recommender ===");
        System.out.print("Enter your username: ");
        String username = scanner.nextLine();
        int userId = addUser(username);

        boolean running = true;
        while (running) {
            System.out.println("\n=== Moodvie ===");
            System.out.println("1. Ask AI for a movie");
            System.out.println("2. Trending now");
            System.out.println("3. My saved movies");
            System.out.println("4. Exit");
            System.out.print("Choose: ");

            String choice = scanner.nextLine();
            switch (choice) {
                case "1":
                    System.out.print("\nTell me what kind of movie you want: ");
                    String userRequest = scanner.nextLine();

                    System.out.println("AI is thinking...");
                    String keyword = extractKeyword(userRequest);
                    System.out.println("Fetching movies about: " + keyword);

                    java.util.List<org.json.JSONObject> movies = fetchMoviesFromTMDB(keyword);
                    System.out.println("AI is reading movie stories...");
                    java.util.List<org.json.JSONObject> finalMovies = aiPickBestMovies(userRequest, movies);

                    if (!finalMovies.isEmpty()) {
                        System.out.print("\nSave a movie? Enter number (or 0 to skip): ");
                        String saveChoice = scanner.nextLine();
                        try {
                            int saveIdx = Integer.parseInt(saveChoice) - 1;
                            if (saveIdx >= 0 && saveIdx < Math.min(5, finalMovies.size())) {
                                org.json.JSONObject saved = finalMovies.get(saveIdx);
                                saveMovie(userId, saved.getString("title"),
                                         saved.optDouble("vote_average", 0.0));
                            }
                        } catch (NumberFormatException e) {
                            System.out.println("Skipped.");
                        }
                    }
                    break;

                case "2":
                    showTrending();
                    break;

                case "3":
                    showSavedMovies(userId);
                    break;

                case "4":
                    running = false;
                    System.out.println("Goodbye!");
                    break;

                default:
                    System.out.println("Invalid choice.");
            }
        }
        scanner.close();
    }
}