package dev.mattest.supplychain.service.impl;

import org.jsoup.Jsoup;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ProductDetailSanitizer {
    private static final Pattern MEDIA_PATH = Pattern.compile("/api/v1/media/([1-9][0-9]*)");
    private static final Cleaner CLEANER = new Cleaner(Safelist.none()
            .addTags("p", "br", "h1", "h2", "h3", "h4", "h5", "h6",
                    "ul", "ol", "li", "strong", "b", "em", "i", "img")
            .addAttributes("img", "src", "alt"));

    public SanitizedDetail sanitize(String html) {
        if (html == null || html.isBlank()) {
            return new SanitizedDetail(html, Set.of());
        }

        org.jsoup.nodes.Document document = Jsoup.parseBodyFragment(html);
        Set<Long> imageIds = new LinkedHashSet<>();
        for (org.jsoup.nodes.Element image : document.select("img")) {
            Matcher matcher = MEDIA_PATH.matcher(image.attr("src"));
            if (!matcher.matches()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Product detail images must use registered media URLs");
            }
            try {
                imageIds.add(Long.parseLong(matcher.group(1)));
            } catch (NumberFormatException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid media reference");
            }
        }

        org.jsoup.nodes.Document cleanedDocument = CLEANER.clean(document);
        cleanedDocument.outputSettings().prettyPrint(false);
        String cleaned = cleanedDocument.body().html();
        return new SanitizedDetail(cleaned, imageIds);
    }

    public record SanitizedDetail(String html, Set<Long> mediaIds) {
    }
}
