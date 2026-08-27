package com.commercecore.cart;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercecore.AbstractIntegrationTest;
import com.commercecore.shared.ApiError;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class CartApiTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createsAndReadsEmptyCart() {
        ResponseEntity<CartIdResponse> createResponse =
            restTemplate.postForEntity("/api/carts", null, CartIdResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID cartId = createResponse.getBody().id();
        assertThat(cartId).isNotNull();

        ResponseEntity<CartResponse> getResponse =
            restTemplate.getForEntity("/api/carts/{cartId}", CartResponse.class, cartId);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().id()).isEqualTo(cartId);
        assertThat(getResponse.getBody().items()).isEmpty();
    }

    @Test
    void unknownCartReturnsNotFound() {
        ResponseEntity<ApiError> response =
            restTemplate.getForEntity("/api/carts/{cartId}", ApiError.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("cart_not_found");
    }
}
