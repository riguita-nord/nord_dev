package com.nordlab.forge;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;

@QuarkusTest
class CoreSmokeTest {
    @Test
    void coreStartsAndInitializesSchema() {
        given()
            .when().get("/health")
            .then()
            .statusCode(200)
            .body("ok", equalTo(true))
            .body("service", equalTo("nord-forge"))
            .body("version", equalTo("2.0.0"));
    }
}
