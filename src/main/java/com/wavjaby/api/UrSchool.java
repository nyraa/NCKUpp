package com.wavjaby.api;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.wavjaby.json.JsonBuilder;
import com.wavjaby.json.JsonObject;
import com.wavjaby.logger.Logger;
import com.wavjaby.logger.ProgressBar;
import org.jsoup.Connection;
import org.jsoup.helper.HttpConnection;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import java.io.*;
import java.net.CookieManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.wavjaby.Cookie.getDefaultCookie;
import static com.wavjaby.Lib.parseUrlEncodedForm;
import static com.wavjaby.Lib.setAllowOrigin;
import static com.wavjaby.Main.pool;

public class UrSchool implements HttpHandler {
    private static final String TAG = "[UrSchool] ";

    private String UrSchoolData;
    private final long updateInterval = 30 * 60 * 1000;
    private long lastUpdateTime;

    public UrSchool() {
        File file = new File("./urschool.json");
        if (file.exists()) {
            try {
                InputStream reader = Files.newInputStream(file.toPath());
                ByteArrayOutputStream out = new ByteArrayOutputStream();

                int len;
                byte[] buff = new byte[1024];
                while ((len = reader.read(buff)) != -1)
                    out.write(buff, 0, len);

                UrSchoolData = out.toString("UTF-8");
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (System.currentTimeMillis() - file.lastModified() < updateInterval) {
                Logger.log(TAG, "Up to date");
                return;
            }
        }
        updateUrSchoolData();
    }

    @Override
    public void handle(HttpExchange req) {
        pool.submit(() -> {
            long startTime = System.currentTimeMillis();
            CookieManager cookieManager = new CookieManager();
            Headers requestHeaders = req.getRequestHeaders();
            getDefaultCookie(requestHeaders, cookieManager);

            try {
                JsonBuilder data = new JsonBuilder();

                String queryString = req.getRequestURI().getQuery();
                boolean success = true;
                if (queryString == null) {
                    if (System.currentTimeMillis() - lastUpdateTime > updateInterval)
                        updateUrSchoolData();
                    data.append("data", UrSchoolData, true);
                } else {
                    success = getInstructorInfo(queryString, data);
                }

                Headers responseHeader = req.getResponseHeaders();
                byte[] dataByte = data.toString().getBytes(StandardCharsets.UTF_8);
                responseHeader.set("Content-Type", "application/json; charset=UTF-8");

                // send response
                setAllowOrigin(requestHeaders, responseHeader);
                req.sendResponseHeaders(success ? 200 : 400, dataByte.length);
                OutputStream response = req.getResponseBody();
                response.write(dataByte);
                response.flush();
                req.close();
            } catch (IOException e) {
                req.close();
            }
            Logger.log(TAG, "Get UrSchool " + (System.currentTimeMillis() - startTime) + "ms");
        });
    }

    private boolean getInstructorInfo(String queryString, JsonBuilder outData) {
        Map<String, String> query = parseUrlEncodedForm(queryString);
        String instructorID = query.get("id");
        if (instructorID == null) {
            outData.append("err", TAG + "Query id not found");
            return false;
        }
        String getMode = query.get("mode");
        if (getMode == null) {
            outData.append("err", TAG + "Query mode not found");
            return false;
        }

        try {
            Connection.Response result;
            while (true) {
                try {
                    result = HttpConnection.connect("https://urschool.org/ajax/modal/" + instructorID + "?mode=" + getMode)
                            .ignoreContentType(true)
                            .userAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/50.0.2661.75 Safari/537.36")
                            .header("X-Requested-With", "XMLHttpRequest")
                            .timeout(3 * 1000)
                            .execute();
                    break;
                } catch (IOException ignore) {
                }
            }
            String resultBody = result.body();

            // Get tags range
            int tagsStart, tagsEnd;
            if ((tagsStart = resultBody.indexOf("<div class='col-md-12'>")) == -1 ||
                    (tagsEnd = resultBody.indexOf("</div>", tagsStart += 23)) == -1
            ) {
                outData.append("err", TAG + "Tags not found");
                return false;
            }

            // Get tags
            StringBuilder tageBuilder = new StringBuilder();
            int urlStart, urlEnd = tagsStart;
            while (true) {
                // Get tag url
                if ((urlStart = resultBody.indexOf("href='", urlEnd + 1)) == -1 ||
                        (urlEnd = resultBody.indexOf('\'', urlStart += 6)) == -1
                ) {
                    outData.append("err", TAG + "Tag url not found");
                    return false;
                }
                String url = resultBody.substring(urlStart, urlEnd);
                if (url.startsWith("/shop") || urlStart > tagsEnd) break;

                // Get tag name
                int tagNameStart, tagNameEnd;
                if ((tagNameStart = resultBody.indexOf('>', urlEnd + 1)) == -1 ||
                        (tagNameEnd = resultBody.indexOf('<', tagNameStart += 1)) == -1
                ) {
                    outData.append("err", TAG + "Tag name not found");
                    return false;
                }
                String tagName = resultBody.substring(tagNameStart, tagNameEnd).trim();

                tageBuilder.append(',').append('[')
                        .append('"').append(url).append('"').append(',')
                        .append('"').append(tagName).append('"')
                        .append(']');
            }
            if (tageBuilder.length() > 0) tageBuilder.setCharAt(0, '[');
            else tageBuilder.append('[');
            tageBuilder.append(']');

            // Get visitors
            int countStart, countEnd;
            if ((countStart = resultBody.indexOf("store.count")) == -1 ||
                    (countStart = resultBody.indexOf('=', countStart + 11)) == -1 ||
                    (countEnd = resultBody.indexOf(';', countStart += 1)) == -1
            ) {
                outData.append("err", TAG + "Visitors not found");
                return false;
            }
            StringBuilder visitorsBuilder = new StringBuilder();
            int takeCourseCount = Integer.parseInt(resultBody.substring(countStart, countEnd).trim());
            int visitorStart, visitorEnd = countEnd;
            while (true) {
                // Get profile url
                if ((visitorStart = resultBody.indexOf("store.visits.push(", visitorEnd + 1)) == -1
                ) {
                    outData.append("err", TAG + "Profile url not found");
                    return false;
                }
                if (resultBody.startsWith("store", visitorStart += 18)) break;
                if ((visitorStart = resultBody.indexOf('\'', visitorStart)) == -1 ||
                        (visitorEnd = resultBody.indexOf('\'', visitorStart += 1)) == -1) {
                    outData.append("err", TAG + "Profile url string not found");
                    return false;
                }
                String url = resultBody.substring(visitorStart, visitorEnd);

                int subStart, subEnd;
                if ((subEnd = url.lastIndexOf('/')) == -1 ||
                        (subStart = url.lastIndexOf('/', subEnd -= 1)) == -1) {
                    outData.append("err", TAG + "Profile url parse error");
                    return false;
                }
                url = url.substring(subStart + 1, subEnd + 1);

                visitorsBuilder.append(',').append('"').append(url).append('"');
            }
            if (visitorsBuilder.length() > 0) visitorsBuilder.setCharAt(0, '[');
            else visitorsBuilder.append('[');
            visitorsBuilder.append(']');

            // Get comment
            StringBuilder commentBuilder = new StringBuilder();
            int commentStart, commentEnd = visitorEnd;
            while (true) {
                // Get comment data
                if ((commentStart = resultBody.indexOf("var obj", commentEnd + 1)) == -1 ||
                        (commentStart = resultBody.indexOf('{', commentStart += 7)) == -1)
                    break;

                if ((commentEnd = resultBody.indexOf('}', commentStart + 1)) == -1) {
                    outData.append("err", TAG + "Comment data not found");
                    return false;
                }
                JsonObject commentData = new JsonObject(resultBody.substring(commentStart, commentEnd + 1));
                commentData.remove("cafe_id");
                commentData.put("body", parseUnicode(commentData.getString("body")));

                // Get avatar
                int limit = resultBody.indexOf('}', commentEnd + 1);
                int avatarStart, avatarEnd;
                if ((avatarStart = resultBody.indexOf("avatar", commentEnd + 1)) == -1 ||
                        (avatarStart = resultBody.indexOf('\'', avatarStart + 6)) == -1 ||
                        (avatarEnd = resultBody.indexOf('\'', avatarStart += 1)) == -1 ||
                        avatarEnd > limit) {
                    outData.append("err", TAG + "Profile url string not found");
                    return false;
                }
                String url = resultBody.substring(avatarStart, avatarEnd);
                if (url.contains("anonymous"))
                    url = null;
                else {
                    int subStart, subEnd;
                    if ((subEnd = url.lastIndexOf('/')) == -1 ||
                            (subStart = url.lastIndexOf('/', subEnd -= 1)) == -1) {
                        outData.append("err", TAG + "Profile url parse error");
                        return false;
                    }
                    url = url.substring(subStart + 1, subEnd + 1);
                }
                commentData.put("profile", url);

                // Get timestamp
                int timestampStart, timestampEnd;
                if ((timestampStart = resultBody.indexOf("timestamp", commentEnd + 1)) == -1 ||
                        (timestampStart = resultBody.indexOf('\'', timestampStart + 9)) == -1 ||
                        (timestampEnd = resultBody.indexOf('\'', timestampStart += 1)) == -1 ||
                        timestampEnd > limit) {
                    outData.append("err", TAG + "Profile url string not found");
                    return false;
                }
                String timestamp = resultBody.substring(timestampStart, timestampEnd);
                commentData.put("timestamp", Long.parseLong(timestamp));

                commentBuilder.append(',').append(commentData);
            }
            if (commentBuilder.length() > 0) commentBuilder.setCharAt(0, '[');
            else commentBuilder.append('[');
            commentBuilder.append(']');


            // Write result
            JsonBuilder jsonBuilder = new JsonBuilder();
            jsonBuilder.append("id", '"' + instructorID + '"', true);
            jsonBuilder.append("tags", tageBuilder.toString(), true);
            jsonBuilder.append("takeCourseCount", takeCourseCount);
            jsonBuilder.append("takeCourseUser", visitorsBuilder.toString(), true);
            jsonBuilder.append("comments", commentBuilder.toString(), true);
            outData.append("data", jsonBuilder.toString(), true);

            return true;
        } catch (Exception e) {
            outData.append("err", TAG + "Unknown error: " + Arrays.toString(e.getStackTrace()));
            return false;
        }
    }

    private void updateUrSchoolData() {
        pool.submit(() -> {
            long start = System.currentTimeMillis();
            lastUpdateTime = start;

            int[] maxPage = new int[1];
            StringBuilder result = new StringBuilder();

            // Get first page
            ProgressBar progressBar = new ProgressBar(TAG + "Update data ");
            Logger.addProgressBar(progressBar);
            progressBar.setProgress(0f);
            String firstPage = fetchUrSchoolData(1, maxPage);
            progressBar.setProgress((float) 1 / maxPage[0] * 100);
            if (firstPage == null)
                return;
            result.append(firstPage);

            // Get the rest of the page
            ExecutorService readPool = Executors.newFixedThreadPool(16);
            CountDownLatch count = new CountDownLatch(maxPage[0] - 1);
            for (int i = 1; i < maxPage[0]; i++) {
                int finalI = i + 1;
                readPool.submit(() -> {
                    String page = fetchUrSchoolData(finalI, null);
                    synchronized (result) {
                        result.append(page);
                    }
                    progressBar.setProgress(((float) (maxPage[0] - count.getCount() + 1) / maxPage[0]) * 100);
                    count.countDown();
                });
            }
            // Wait result
            try {
                count.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
                return;
            }
            progressBar.setProgress(100f);
            Logger.removeProgressBar(progressBar);
            if (result.length() > 0) result.setCharAt(0, '[');
            else result.append('[');
            result.append(']');
            String resultString = result.toString();
            UrSchoolData = resultString;
            try {
                File file = new File("./urschool.json");
                FileWriter fileWriter = new FileWriter(file);
                fileWriter.write(resultString);
                fileWriter.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            Logger.log(TAG, "Used " + (System.currentTimeMillis() - start) + "ms");
        });
    }

    private List<String[]> fetchFreeProxy() {
        try {
            List<String[]> proxy = new ArrayList<>();
            Connection.Response result = HttpConnection.connect("https://free-proxy-list.net/")
                    .ignoreContentType(true)
                    .execute();
            String resultBody = result.body();
            int resultTableStart;
            if ((resultTableStart = resultBody.indexOf("<table")) == -1) {
                Logger.log(TAG, "Result table not found");
                return null;
            }
            // get table body
            int resultTableBodyStart, resultTableBodyEnd;
            if ((resultTableBodyStart = resultBody.indexOf("<tbody", resultTableStart + 7)) == -1 ||
                    (resultTableBodyEnd = resultBody.indexOf("</tbody>", resultTableBodyStart + 6)) == -1
            ) {
                Logger.log(TAG, "Result table body not found");
                return null;
            }

            // parse table
            String bodyStr = resultBody.substring(resultTableBodyStart, resultTableBodyEnd + 8);
            Node tbody = Parser.parseFragment(bodyStr, new Element("tbody"), "").get(0);

            for (Element i : ((Element) tbody).getElementsByTag("tr")) {
                Elements elements = i.getElementsByTag("td");
                String[] data = elements.stream().map(Element::text).toArray(String[]::new);
                proxy.add(data);
            }
            return proxy;
        } catch (IOException e) {
            return null;
        }
    }

    private String fetchUrSchoolData(int page, int[] maxPage) {
        try {
            String resultBody;
            while (true) {
                try {
                    Connection.Response result = HttpConnection.connect("https://urschool.org/ncku/list?page=" + page)
                            .ignoreContentType(true)
                            .userAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/50.0.2661.75 Safari/537.36")
                            .header("X-Requested-With", "XMLHttpRequest")
                            .timeout(10 * 1000)
                            .execute();
                    resultBody = result.body();
                    break;
                } catch (IOException ignore) {
                }
            }

            if (maxPage != null) {
                int maxPageStart, maxPageEnd;
                if ((maxPageStart = resultBody.lastIndexOf("https://urschool.org/ncku/list?page=")) == -1 ||
                        (maxPageStart = resultBody.lastIndexOf("https://urschool.org/ncku/list?page=", maxPageStart - 36)) == -1 ||
                        (maxPageEnd = resultBody.indexOf('"', maxPageStart + 36)) == -1) {
                    Logger.log(TAG, "Max page number not found");
                    return null;
                }
                maxPage[0] = Integer.parseInt(resultBody.substring(maxPageStart + 36, maxPageEnd));
            }

            int resultTableStart;
            if ((resultTableStart = resultBody.indexOf("<table")) == -1) {
                Logger.log(TAG, "Result table not found");
                return null;
            }
            // get table body
            int resultTableBodyStart, resultTableBodyEnd;
            if ((resultTableBodyStart = resultBody.indexOf("<tbody", resultTableStart + 7)) == -1 ||
                    (resultTableBodyEnd = resultBody.indexOf("</tbody>", resultTableBodyStart + 6)) == -1
            ) {
                Logger.log(TAG, "Result table body not found");
                return null;
            }

            // parse table
            String bodyStr = resultBody.substring(resultTableBodyStart, resultTableBodyEnd + 8);
            Node tbody = Parser.parseFragment(bodyStr, new Element("tbody"), "").get(0);

            StringBuilder out = new StringBuilder();
            for (Element i : ((Element) tbody).getElementsByTag("tr")) {
                String id = i.attr("onclick");
                int idStart, idEnd;
                if ((idStart = id.indexOf('\'')) == -1 ||
                        (idEnd = id.indexOf('\'', idStart + 1)) == -1
                ) {
                    Logger.log(TAG, "Instructor ID not found");
                    return null;
                }
                int modeStart, modeEnd;
                if ((modeStart = id.indexOf('\'', idEnd + 1)) == -1 ||
                        (modeEnd = id.indexOf('\'', modeStart + 1)) == -1
                ) {
                    Logger.log(TAG, "Open mode not found");
                    return null;
                }
                String mode = id.substring(modeStart + 1, modeEnd);
                id = id.substring(idStart + 1, idEnd);

                StringBuilder builder = new StringBuilder();
                builder.append(',').append('"').append(id).append('"');
                builder.append(',').append('"').append(mode).append('"');

                Elements elements = i.getElementsByTag("td");
                for (int j = 0; j < elements.size(); j++) {
                    Element element = elements.get(j);
                    String text = element.text();
                    if (j > 2 && j < 8) {
                        int end = text.lastIndexOf(' ');
                        if (end != -1)
                            text = text.substring(0, end);
                        else
                            text = "-1";
                        builder.append(',').append(text);
                    } else
                        builder.append(',').append('"').append(text.replace("\\", "\\\\")).append('"');
                }
                builder.setCharAt(0, '[');
                builder.append(']');
                out.append(',').append(builder);
            }
            return out.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String parseUnicode(String input) {
        int lastIndex = 0, index;
        int length = input.length();
        index = input.indexOf("\\u");
        StringBuilder builder = new StringBuilder();
        while (index > -1) {
            if (index > (length - 6)) break;
            int nuiCodeStart = index + 2;
            int nuiCodeEnd = nuiCodeStart + 4;
            String substring = input.substring(nuiCodeStart, nuiCodeEnd);
            int number = Integer.parseInt(substring, 16);

            builder.append(input, lastIndex, index);
            builder.append((char) number);

            lastIndex = nuiCodeEnd;
            index = input.indexOf("\\u", nuiCodeEnd);
        }
        builder.append(input, lastIndex, length);
        return builder.toString();
    }
}
