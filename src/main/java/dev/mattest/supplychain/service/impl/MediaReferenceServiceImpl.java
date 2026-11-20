package dev.mattest.supplychain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.mattest.supplychain.entity.CustomerService;
import dev.mattest.supplychain.entity.Product;
import dev.mattest.supplychain.entity.ProductGallery;
import dev.mattest.supplychain.mapper.CustomerServiceMapper;
import dev.mattest.supplychain.mapper.ProductGalleryMapper;
import dev.mattest.supplychain.mapper.ProductMapper;
import dev.mattest.supplychain.service.MediaReferenceService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MediaReferenceServiceImpl implements MediaReferenceService {
    private final ProductMapper productMapper;
    private final ProductGalleryMapper galleryMapper;
    private final CustomerServiceMapper customerServiceMapper;
    private final ProductDetailSanitizer detailSanitizer;

    public MediaReferenceServiceImpl(
            ProductMapper productMapper,
            ProductGalleryMapper galleryMapper,
            CustomerServiceMapper customerServiceMapper,
            ProductDetailSanitizer detailSanitizer) {
        this.productMapper = productMapper;
        this.galleryMapper = galleryMapper;
        this.customerServiceMapper = customerServiceMapper;
        this.detailSanitizer = detailSanitizer;
    }

    @Override
    public boolean isReferenced(long mediaId) {
        if (productMapper.selectCount(new LambdaQueryWrapper<Product>()
                .eq(Product::getMainImageMediaId, mediaId)) > 0) {
            return true;
        }
        if (galleryMapper.selectCount(new LambdaQueryWrapper<ProductGallery>()
                .eq(ProductGallery::getMediaId, mediaId)) > 0) {
            return true;
        }
        if (customerServiceMapper.selectCount(new LambdaQueryWrapper<CustomerService>()
                .eq(CustomerService::getQrMediaId, mediaId)) > 0) {
            return true;
        }
        List<String> detailHtml = productMapper.selectList(new LambdaQueryWrapper<Product>()
                        .isNotNull(Product::getDetailHtml))
                .stream()
                .map(Product::getDetailHtml)
                .toList();
        for (String html : detailHtml) {
            if (detailSanitizer.extractMediaIds(html).contains(mediaId)) {
                return true;
            }
        }
        return false;
    }
}
