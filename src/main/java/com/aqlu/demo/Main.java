package com.aqlu.demo;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.WebDriverWait;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.lang.Math.abs;

/**
 * Main
 * Created by aqlu on 2017/11/27.
 */
@Slf4j
public class Main {

    public static void main(String[] args) throws InterruptedException, IOException {

        boolean saveTempFile = false; // 是否保存还原的背景图片
        String url = "http://www.jsgsj.gov.cn:58888/province/";
        String keyword = "苏宁";


        WebDriver webDriver = new ChromeDriver();
        try {
            // 打开搜索页面
            webDriver.get(url);

            try {
                waitElementDisplay(webDriver, 10, "#name");
            } catch (Exception e) {
                log.warn("等待搜索框展示超时");
                return;
            }

            // 输入关键字
            WebElement inputElement = webDriver.findElement(By.cssSelector("#name"));
            inputElement.sendKeys(keyword);
            log.info("输入关键字:{}", keyword);
            TimeUnit.MILLISECONDS.sleep(100);
            inputElement.sendKeys(Keys.ENTER);
            log.info("输入`enter`");

            // 等待Geetest图片加载完
            try {
                waitElementDisplay(webDriver, 10, "div.gt_cut_fullbg_slice", "div.gt_cut_bg_slice", "div.gt_slider_knob");
            } catch (Exception e) {
                log.warn("等待验证图片展示超时");
                return;
            }

            boolean successFlag = false;
            int i = 0;
            while (i < 3) { // 校验失败最多重试两次，总共3次
                long begin = System.currentTimeMillis();

                // 下载完整的验证图
                BufferedImage fullBgImage = getGeetestImage(webDriver, "div.gt_cut_fullbg_slice", saveTempFile);

                // 下载有缺口的验证图
                BufferedImage cutBgImage = getGeetestImage(webDriver, "div.gt_cut_bg_slice", saveTempFile);

                // 对比两张验证图，获得缺口的位置(x_offset)
                int diffX = getDiffX(fullBgImage, cutBgImage);
                log.debug("缺口的位置，x:", diffX);

                // 根据缺口位置计算移动轨迹
                List<Map<String, Integer>> tracks = getTrack(diffX);

                // 移动滑块
                boolean result = simulateDrag(webDriver, tracks);
                log.info("验证码破解：{}, 耗时：{}ms. keyword:{}", result, System.currentTimeMillis() - begin, keyword);

                if (result) {
                    successFlag = true;
                    break;
                } else {
                    i++;
                    log.warn("校验失败，3秒后第{}次重试，keyword:{}", i, keyword);
                    TimeUnit.SECONDS.sleep(3); // 当发生"怪物吃掉了饼"时，页面会暂停3秒。所以此处需要休眠5秒再重试
                }
            }

            if (successFlag) {
                // TODO 处理搜索结果

                try {
                    // 获取搜索结果
                    waitElementDisplay(webDriver, 5, "a.listbox > a");

                    List<WebElement> hrefElements = webDriver.findElements(By.cssSelector(".listbox > a"));
                    List<String> detailUrls = hrefElements.stream().map(href -> href.getAttribute("href")).collect(Collectors.toList());

                    log.debug("公司连接：{}", detailUrls);

                } catch (Exception e) {
                    log.warn("获取搜索结果失败");
                }
            }
        } finally {
            webDriver.quit();
        }
    }

    private static Pattern urlPattern = Pattern.compile("https?://(www\\.)?[-a-zA-Z0-9@:%._+~#=]{2,256}\\.[a-z]{2,6}\\b([-a-zA-Z0-9@:%_+.~#?&/=]*)");
    private static Pattern positionPattern = Pattern.compile("[-]?[\\d]+(?=px)");

    /**
     * 将Geetest打乱的图片还原，Geetest的原始背景图是分成52份碎片乱序组合的。目前是上下各26份，每份碎片图片宽10px， 高58px。
     *
     * @param image     原始背景图
     * @param locations 展示位置列表，数据结构：[{x=-25, y=-58}, ...]
     * @return 顺序排列好的图片
     */
    public static BufferedImage recover(BufferedImage image, List<Map<String, Integer>> locations) throws IOException {
        long begin = System.currentTimeMillis();

        int per_image_with = 10;  // 每张碎片图片的宽度
        int per_image_height = 58; // 每张碎片图片的高度


        List<BufferedImage> upperList = new ArrayList<>();
        List<BufferedImage> downList = new ArrayList<>();

        // 将原始图片裁剪成碎片
        for (Map<String, Integer> location : locations) {
            int x = location.get("x");
            int y = location.get("y");
            if (y == -58) {
                upperList.add(image.getSubimage(abs(x), 58, per_image_with, per_image_height));
            } else if (y == 0) {
                downList.add(image.getSubimage(abs(x), 0, per_image_with, per_image_height));
            }
        }

        BufferedImage newImage = new BufferedImage(upperList.size() * per_image_with, image.getHeight(), image.getType());

        // 重绘图片的上半部分
        int x_offset = 0;
        for (BufferedImage bufferedImage : upperList) {
            Graphics graphics = newImage.getGraphics();
            graphics.drawImage(bufferedImage, x_offset, 0, null);
            x_offset += bufferedImage.getWidth();
        }

        // 重绘图片的下半部分
        x_offset = 0;
        for (BufferedImage bufferedImage : downList) {
            Graphics graphics = newImage.getGraphics();
            graphics.drawImage(bufferedImage, x_offset, 58, null);
            x_offset += bufferedImage.getWidth();
        }

        log.debug("还原图片耗时：{}ms", System.currentTimeMillis() - begin);
        return newImage;
    }

    /**
     * 计算验证图的缺口位置（x轴） 两张原始图的大小都是相同的260*116，那就通过两个for循环依次对比每个像素点的RGB值， 如果RGB三元素中有一个相差超过50则就认为找到了缺口的位置
     *
     * @param image1 图像1
     * @param image2 图像2
     * @return 缺口的x坐标
     */
    public static int getDiffX(BufferedImage image1, BufferedImage image2) {
        long begin = System.currentTimeMillis();

        for (int x = 0; x < image1.getWidth(); x++) {
            for (int y = 0; y < image1.getHeight(); y++) {
                if (!isSimilar(image1, image2, x, y)) {
                    return x;
                }
            }
        }

        log.debug("图片对比耗时：{}ms", System.currentTimeMillis() - begin);
        return 0;
    }

    /**
     * 判断image1, image2的[x, y]这一像素是否相似，如果该像素的RGB值相差都在50以内，则认为相似。
     *
     * @param image1   图像1
     * @param image2   图像2
     * @param x_offset x坐标
     * @param y_offset y坐标
     * @return 是否相似
     */
    public static boolean isSimilar(BufferedImage image1, BufferedImage image2, int x_offset, int y_offset) {
        Color pixel1 = new Color(image1.getRGB(x_offset, y_offset));
        Color pixel2 = new Color(image2.getRGB(x_offset, y_offset));

        return abs(pixel1.getBlue() - pixel2.getBlue()) < 50 && abs(pixel1.getGreen() - pixel2.getGreen()) < 50 && abs(pixel1.getRed() - pixel2.getRed()) < 50;
    }

    /**
     * 根据缺口位置x_offset，仿照手动拖动滑块时的移动轨迹。
     * 手动拖动滑块有几个特点：
     * 开始时拖动速度快，最后接近目标时会慢下来；
     * 总时间大概1~3秒；
     * 有可能会拖超过后再拖回头；
     *
     * @return 返回一个轨迹数组，数组中的每个轨迹都是[x,y,z]三元素：x代表横向位移，y代表竖向位移，z代表时间间隔，单位毫秒
     */
    private static List<Map<String, Integer>> getTrack(int x_offset) {
        List<Map<String, Integer>> tracks;
        long begin = System.currentTimeMillis();

        // 实际上滑块的起始位置并不是在图像的最左边，而是大概有6个像素的距离，所以滑动距离要减掉这个长度
        x_offset = x_offset - 6;

        if (getRandom(0, 10) % 2 == 0) {
            tracks = strategics_1(x_offset);
        } else {
            tracks = strategics_2(x_offset);
        }

        log.debug("生成轨迹耗时: {}ms", System.currentTimeMillis() - begin);
        log.debug("计算出移动轨迹: {}", tracks);
        return tracks;
    }

    /**
     * 轨迹策略1
     */
    private static List<Map<String, Integer>> strategics_1(int x_offset) {
        List<Map<String, Integer>> tracks = new ArrayList<>();
        float totalTime = 0;

        int x = getRandom(1, 3);

        // 随机按1~3的步长生成各个点
        while (x_offset - x >= 5) {
            Map<String, Integer> point = new HashMap<>(3);
            point.put("x", x);
            point.put("y", 0);
            point.put("z", 0);
            tracks.add(point);

            x_offset = x_offset - x;
            x = getRandom(1, 5);
            totalTime += point.get("z").floatValue();
        }

        // 后面几个点放慢时间
        for (int i = 0; i < x_offset; i++) {
            Map<String, Integer> point = new HashMap<>(3);
            point.put("x", 1);
            point.put("y", 0);
            point.put("z", getRandom(10, 200));

            tracks.add(point);
            totalTime += point.get("z").floatValue();
        }

        log.debug("预计拖拽耗时: {}ms", totalTime);
        return tracks;
    }

    /**
     * 轨迹策略2
     */
    private static List<Map<String, Integer>> strategics_2(int x_offset) {
        List<Map<String, Integer>> tracks = new ArrayList<>();
        float totalTime = 0;

        int dragX = 0; // 已拖拽的横向偏移量
        int nearRange = getRandom(5, 10); // 靠近缺口的范围
        while (dragX < x_offset - nearRange) { // 生成快速拖拽点，拖拽距离非常靠近切口
            int stepLength = getRandom(1, 5); // 随机按1~5的步长生成各个点
            Map<String, Integer> point = new HashMap<>(3);
            point.put("x", stepLength);
            point.put("y", 0);
            point.put("z", getRandom(0, 2));
            tracks.add(point);

            totalTime += point.get("z").floatValue();
            dragX += stepLength;
        }

        // 随机一定的比例将滑块拖拽过头
        if (getRandom(0, 99) % 2 == 0) {
            int stepLength = getRandom(10, 15); // 随机按1~5的步长生成各个点
            Map<String, Integer> attachPoint = new HashMap<>(3);
            attachPoint.put("x", stepLength);
            attachPoint.put("y", 0);
            attachPoint.put("z", getRandom(0, 2));
            tracks.add(attachPoint);

            dragX += stepLength;
            totalTime += attachPoint.get("z").floatValue();
        }

        // 精确点
        for (int i = 0; i < Math.abs(dragX - x_offset); i++) {
            if (dragX > x_offset) {
                Map<String, Integer> point = new HashMap<>(3);
                point.put("x", -1);
                point.put("y", 0);
                point.put("z", getRandom(10, 100));
                tracks.add(point);

                totalTime += point.get("z").floatValue();
            } else {
                Map<String, Integer> point = new HashMap<>(3);
                point.put("x", 1);
                point.put("y", 0);
                point.put("z", getRandom(10, 100));
                tracks.add(point);

                totalTime += point.get("z").floatValue();
            }
        }

        log.debug("预计拖拽耗时: {}ms", totalTime);
        return tracks;
    }

    /**
     * 根据移动轨迹，模拟拖动极验的验证滑块
     */
    private static boolean simulateDrag(WebDriver webDriver, List<Map<String, Integer>> tracks) throws InterruptedException {
        log.debug("开始模拟拖动滑块");

        WebElement slider = webDriver.findElement(By.cssSelector("div.gt_slider_knob"));
        log.debug("滑块初始位置: {}", slider.getLocation());

        Actions actions = new Actions(webDriver);
        actions.clickAndHold(slider).perform();

        for (Map<String, Integer> point : tracks) {
            int x = point.get("x") + 22;
            int y = point.get("y") + 22;

            actions.moveToElement(slider, x, y).perform();

            int z = point.get("z");
            TimeUnit.MILLISECONDS.sleep(z);
        }

        TimeUnit.MILLISECONDS.sleep(getRandom(100, 200)); // 随机停顿100~200毫秒
        actions.release(slider).perform();

        TimeUnit.MILLISECONDS.sleep(100); // 等待0.1秒后检查结果

        try {
            // 在5秒之内检查弹出框是否消失，如果消失则说明校验通过；如果没有消失说明校验失败。
            new WebDriverWait(webDriver, 5).until((ExpectedCondition<Boolean>) driver -> {
                try {
                    WebElement popupElement = driver.findElement(By.cssSelector("div.gt_popup_wrap"));
                    return !popupElement.isDisplayed();
                } catch (NoSuchElementException e) {
                    return true; // 元素不存在也返回true
                }
            });

            return true;
        } catch (Exception e) {
            return false;
        }
    }


    /**
     * 根据指定区间返回随机数,包含最小值与最大值[min, max]
     *
     * @param min 最小值
     * @param max 最大值
     * @return 指定区间的随机数
     */
    private static int getRandom(int min, int max) {
        return min + (int) ((max + 1 - min) * Math.random());
    }

    /**
     * 等待元素出现
     *
     * @param webDriver        see {@link WebDriver}
     * @param timeOutInSeconds 最大等待时长，单位：秒
     * @param cssSelectors     css选择器，可以是多个
     */
    private static void waitElementDisplay(WebDriver webDriver, int timeOutInSeconds, String... cssSelectors) {
        new WebDriverWait(webDriver, timeOutInSeconds).until((ExpectedCondition<Boolean>) driver -> {
            try {
                if (Objects.isNull(driver)) {
                    return false;
                }

                for (String cssSelector : cssSelectors) {
                    if (!driver.findElement(By.cssSelector(cssSelector)).isDisplayed()) {
                        return false;
                    }
                }
                return true;
            } catch (Exception e) {
                log.debug("等待{}元素展示异常, errMsg:{}", cssSelectors, e.getMessage());
                return false;
            }
        });
    }

    /**
     * 下载并还原极验的验证图
     *
     * @param webDriver    see {@link WebDriver}
     * @param cssSelector  验证图所在的html标签的css选择器
     * @param saveTempFile 是否保存临时图片
     */
    private static BufferedImage getGeetestImage(WebDriver webDriver, String cssSelector, boolean saveTempFile) throws IOException {
        log.debug("获取验证图像, cssSelector:{}", cssSelector);

        long begin = System.currentTimeMillis();

        List<WebElement> imageSlices = webDriver.findElements(By.cssSelector(cssSelector));

        if (Objects.isNull(imageSlices) || imageSlices.size() == 0) {
            log.warn("未找到元素，cssSelector:{}", cssSelector);
            return null;
        }

        String divStyle = imageSlices.get(0).getAttribute("style");
        log.debug("div style: {}", divStyle);

        // 获取图像url
        String imageUrl = null;
        Matcher matcher = urlPattern.matcher(divStyle);
        if (matcher.find()) {
            imageUrl = matcher.group();
        }

        if (Objects.isNull(imageUrl)) {
            log.warn("未获取到验证图地址，divStyle:{}", divStyle);
            return null;
        }
        imageUrl = imageUrl.replace("webp", "jpg"); // chrome浏览器得到的验证图是webp格式，其他浏览器都是jpg格式。

        // 获取图像的每个切片位置
        List<Map<String, Integer>> locations = new ArrayList<>();
        for (WebElement imageSlice : imageSlices) {
            Map<String, Integer> location = new HashMap<>(2);

            String styleText = imageSlice.getAttribute("style");
            int x = parsePosition(styleText)[0];
            int y = parsePosition(styleText)[1];

            location.put("x", x);
            location.put("y", y);
            locations.add(location);
        }

        String imageName = new File(imageUrl).getName();

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(imageUrl);
            httpGet.addHeader("Host", "static.geetest.com");
            httpGet.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/56.0.2924.87 Safari/537.36");
            BufferedImage image = ImageIO.read(httpClient.execute(httpGet).getEntity().getContent());
            if (saveTempFile) {
                ImageIO.write(image, "JPG", new File(imageName));
            }

            BufferedImage newImage = recover(image, locations);

            if (saveTempFile) {
                ImageIO.write(newImage, "JPG", new File("re_" + imageName));
            }

            log.debug("下载并还原{}总耗时：{}ms", imageUrl, System.currentTimeMillis() - begin);
            return newImage;
        }
    }


    /**
     * 解析位置
     *
     * @param text like: background-image: url("http://static.geetest.com/pictures/gt/26ebd36a0/26ebd36a0.webp"); background-position: -157px -58px;
     * @return [-157, -58]
     */
    private static int[] parsePosition(String text) {
        int[] position = new int[2];
        Matcher matcher = positionPattern.matcher(text);

        int i = 0;
        while (matcher.find()) {
            if (i > 2) {
                break;
            }
            String str = matcher.group();
            position[i] = Integer.parseInt(str);
            i++;
        }

        return position;
    }
}
