package com.findworks.home;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HomeControllerTest {

    @Test
    void rendersHomeTemplate() {
        assertThat(new HomeController().home()).isEqualTo("home");
    }
}
