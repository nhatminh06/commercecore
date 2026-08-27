package com.commercecore.catalog;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);

    // No price-update endpoint exists yet; this exists so integration tests can prove checkout
    // snapshots price at checkout time regardless of later catalog changes.
    @Modifying
    @Query("UPDATE Product p SET p.priceAmount = :price WHERE p.sku = :sku")
    void updatePrice(@Param("sku") String sku, @Param("price") BigDecimal price);
}
