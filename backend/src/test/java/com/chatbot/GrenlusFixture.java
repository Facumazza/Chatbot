package com.chatbot;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;

/** Respuestas con la forma real de la API de Grenlus, revisada el 2026-11-10. */
final class GrenlusFixture {

    private GrenlusFixture() {
    }

    static String remera(int basePrice) {
        return """
                {"id":1,"nombre":"Remera Personalizada","descripcion":"Remera 24.1 con estampa DTF.","activo":true,
                 "precioBase":%d,"precioEstampaChica":2000,"precioEstampaMedia":3500,"precioEstampaGrande":6000,
                 "descuentoMayoristaPorcentaje":10,"tipo":"INDUMENTARIA","usaTalles":true,
                 "incluyeTallesInfantiles":true,"incluyeTallesEspeciales":true,"incluyeTallesEspecialesGrandes":true,
                 "precioAdicionalTalleEspecial":2500,"anchoChicaCm":15,"altoChicaCm":15,"anchoMediaCm":25,"altoMediaCm":25,
                 "tallesDisponibles":["4","6","8","L","M","S","10","12","14","16","18","T6","T8","XL","T10","T14","T16","XXL"],
                 "usaColores":true,"permiteFrente":true,"permiteEspalda":true,"permiteManga":true,"requiereImagen":true,
                 "permiteEstampaChica":true,"permiteEstampaMedia":true,"permiteEstampaGrande":true}
                """.formatted(basePrice);
    }

    static final String TAZA_INACTIVA = """
            {"id":7,"nombre":"Taza personalizada","descripcion":"Taza sublimada triple A.","activo":false,
             "precioBase":7000,"precioEstampaChica":0,"descuentoMayoristaPorcentaje":10,"tipo":"INDUMENTARIA",
             "usaTalles":false,"usaColores":false,"permiteFrente":true,"permiteEstampaChica":true}
            """;

    static final String TAZA = """
            {"id":8,"nombre":"taza personalizada","descripcion":"Taza sublimada con tu imagen.","activo":true,
             "precioBase":6500,"precioEstampaChica":0,"descuentoMayoristaPorcentaje":10,"tipo":"ARTICULO",
             "usaTalles":false,"usaColores":false,"permiteFrente":true,"permiteEstampaChica":true}
            """;

    static final String REMERA_COLORES = """
            [{"id":1,"nombre":"Negro","precioAdicional":0},{"id":2,"nombre":"Dorado","precioAdicional":1500}]
            """;

    static void stubCatalog(WireMockServer server, String... products) {
        server.stubFor(get(urlEqualTo("/grenlus/indumentarias"))
                .willReturn(okJson("[" + String.join(",", products) + "]")));
        server.stubFor(get(urlEqualTo("/grenlus/indumentarias/1/colores")).willReturn(okJson(REMERA_COLORES)));
    }
}
