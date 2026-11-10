package com.chatbot.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GrenlusProductMapperTests {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void explicitSizesFollowTheWebsiteOrder() {
        assertThat(GrenlusProductMapper.offeredSizes(json.readTree("""
                {"usaTalles":true,"tallesDisponibles":["xl","M","T6","4"]}
                """))).containsExactly("4", "T6", "M", "XL");
    }

    @Test
    void flagsDecideSizesWhenNoneAreListed() {
        assertThat(GrenlusProductMapper.offeredSizes(json.readTree("""
                {"usaTalles":true,"incluyeTallesInfantiles":false,"incluyeTallesEspeciales":true,
                 "incluyeTallesEspecialesGrandes":false}
                """))).containsExactly("T6", "T8", "T10", "S", "M", "L", "XL", "XXL");
    }

    @Test
    void productWithoutSizesHasNone() {
        assertThat(GrenlusProductMapper.offeredSizes(json.readTree("{\"usaTalles\":false}"))).isEmpty();
    }
}
