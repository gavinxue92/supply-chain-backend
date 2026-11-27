package dev.mattest.supplychain.service.impl;

import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 图片解码、缩放与 JPEG 重编码。
 *
 * <p>后台直传的原始图常是 2048×2048 的无损 PNG（单张 5–6 MB），而小程序展示区域
 * 只需 160–1080 像素。这里统一把图片压到可展示的尺寸，避免下载体积失控。
 *
 * <p>WebP 依赖 JDK 之外的解码器，{@link #decode} 对它返回 {@code null}，调用方需要
 * 保留原图而不是报错。
 */
@Component
public class ImageTransformer {
    /** 入库图片的最长边。详情页主图 @3x 约 714 px，1440 已覆盖二倍屏。 */
    public static final int MAX_EDGE = 1440;
    /** 照片在 q82 下肉眼与无损差异极小，体积通常降到原来的 1/10 以内。 */
    public static final float JPEG_QUALITY = 0.82f;
    /** 小于该边长的图片不再缩放，避免把已经合适的图放大。 */
    private static final int MIN_USEFUL_EDGE = 160;

    /** 解码失败或格式不受支持时返回 null。 */
    public BufferedImage decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException exception) {
            return null;
        }
    }

    /** 等比缩放到最长边不超过 {@code maxEdge}；已经足够小时返回原对象。 */
    public BufferedImage scaleToFit(BufferedImage source, int maxEdge) {
        if (source == null) {
            return null;
        }
        int sourceEdge = Math.max(source.getWidth(), source.getHeight());
        if (sourceEdge <= maxEdge) {
            return source;
        }
        int targetWidth = Math.max(1, Math.round(source.getWidth() * (maxEdge / (float) sourceEdge)));
        int targetHeight = Math.max(1, Math.round(source.getHeight() * (maxEdge / (float) sourceEdge)));
        return resize(source, targetWidth, targetHeight);
    }

    /** 编码为 JPEG；带透明通道的图会先铺白底，否则透明区域会变成黑色。 */
    public byte[] toJpeg(BufferedImage source, float quality) {
        if (source == null) {
            return null;
        }
        BufferedImage rgb = source;
        if (source.getColorModel().hasAlpha()) {
            rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rgb.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
                graphics.drawImage(source, 0, 0, null);
            } finally {
                graphics.dispose();
            }
        } else if (source.getType() != BufferedImage.TYPE_INT_RGB
                && source.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rgb.createGraphics();
            try {
                graphics.drawImage(source, 0, 0, null);
            } finally {
                graphics.dispose();
            }
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(buffer)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.setOutput(output);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } catch (IOException exception) {
            return null;
        } finally {
            writer.dispose();
        }
        return buffer.toByteArray();
    }

    /**
     * 生成缩略图字节；解码失败返回 null。
     *
     * @param maxEdge 目标最长边，低于 {@link #MIN_USEFUL_EDGE} 时按该下限处理
     */
    public byte[] toThumbnail(byte[] original, int maxEdge) {
        BufferedImage decoded = decode(original);
        if (decoded == null) {
            return null;
        }
        int edge = Math.max(maxEdge, MIN_USEFUL_EDGE);
        BufferedImage scaled = scaleToFit(decoded, edge);
        return toJpeg(scaled, JPEG_QUALITY);
    }

    /**
     * 入库规格化：最长边压到 {@link #MAX_EDGE} 并转 JPEG。
     *
     * @return 无法解码（如 WebP）或压完反而更大时返回 null，调用方应保留原图
     */
    public byte[] normalize(byte[] original) {
        BufferedImage decoded = decode(original);
        if (decoded == null) {
            return null;
        }
        BufferedImage scaled = scaleToFit(decoded, MAX_EDGE);
        byte[] encoded = toJpeg(scaled, JPEG_QUALITY);
        if (encoded == null || encoded.length >= original.length) {
            return null;
        }
        return encoded;
    }

    /** 判断是否需要重新编码：JPEG 且体积已经不大就跳过，避免迁移时反复转码。 */
    public boolean shouldNormalize(String mediaType, long byteSize) {
        if (!"image/jpeg".equalsIgnoreCase(mediaType)) {
            return true;
        }
        return byteSize > 400L * 1024;
    }

    private BufferedImage resize(BufferedImage source, int targetWidth, int targetHeight) {
        BufferedImage current = source;
        int width = source.getWidth();
        int height = source.getHeight();
        // 逐级减半，单次大幅缩小用双线性会产生明显锯齿。
        while (width > targetWidth * 2 && height > targetHeight * 2) {
            width = Math.max(targetWidth, width / 2);
            height = Math.max(targetHeight, height / 2);
            current = draw(current, width, height);
        }
        return draw(current, targetWidth, targetHeight);
    }

    private BufferedImage draw(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }
}
