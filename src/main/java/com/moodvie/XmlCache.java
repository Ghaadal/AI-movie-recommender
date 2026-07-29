package com.moodvie;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.util.*;

public class XmlCache {

    static final String CACHE_FILE = "movie_cache.xml";

    public static void saveToCache(String keyword, List<Map<String, Object>> movies) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();

            Element root = doc.createElement("cache");
            doc.appendChild(root);

            Element search = doc.createElement("search");
            search.setAttribute("keyword", keyword);
            search.setAttribute("date", new java.util.Date().toString());
            root.appendChild(search);

            for (Map<String, Object> movie : movies) {
                Element m = doc.createElement("movie");
                addElement(doc, m, "title", movie.get("title").toString());
                addElement(doc, m, "year", movie.get("year").toString());
                addElement(doc, m, "rating", movie.get("rating").toString());
                addElement(doc, m, "poster", movie.getOrDefault("poster", "").toString());
                addElement(doc, m, "overview", movie.getOrDefault("overview", "").toString());
                search.appendChild(m);
            }

            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.transform(new DOMSource(doc), new StreamResult(new File(CACHE_FILE)));

            System.out.println("Cached: " + keyword);
        } catch (Exception e) {
            System.out.println("Cache save error: " + e.getMessage());
        }
    }

    public static List<Map<String, Object>> loadFromCache(String keyword) {
        List<Map<String, Object>> movies = new ArrayList<>();
        try {
            File file = new File(CACHE_FILE);
            if (!file.exists()) return movies;

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(file);

            NodeList searches = doc.getElementsByTagName("search");
            for (int i = 0; i < searches.getLength(); i++) {
                Element search = (Element) searches.item(i);
                if (search.getAttribute("keyword").equalsIgnoreCase(keyword)) {
                    NodeList movieNodes = search.getElementsByTagName("movie");
                    for (int j = 0; j < movieNodes.getLength(); j++) {
                        Element m = (Element) movieNodes.item(j);
                        Map<String, Object> movie = new HashMap<>();
                        movie.put("title", getElementText(m, "title"));
                        movie.put("year", getElementText(m, "year"));
                        movie.put("rating", Double.parseDouble(getElementText(m, "rating")));
                        movie.put("poster", getElementText(m, "poster"));
                        movie.put("overview", getElementText(m, "overview"));
                        movies.add(movie);
                    }
                    System.out.println("Loaded from cache: " + keyword);
                    return movies;
                }
            }
        } catch (Exception e) {
            System.out.println("Cache load error: " + e.getMessage());
        }
        return movies;
    }

    private static void addElement(Document doc, Element parent, String name, String value) {
        Element el = doc.createElement(name);
        el.appendChild(doc.createTextNode(value != null ? value : ""));
        parent.appendChild(el);
    }

    private static String getElementText(Element parent, String name) {
        NodeList list = parent.getElementsByTagName(name);
        if (list.getLength() > 0) return list.item(0).getTextContent();
        return "";
    }
}