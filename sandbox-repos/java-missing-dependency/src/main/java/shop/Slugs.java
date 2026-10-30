package shop;

public class Slugs {

    public static String slug(String title) {
        return title.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }
}
