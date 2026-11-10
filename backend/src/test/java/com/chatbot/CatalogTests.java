package com.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import com.chatbot.catalog.CatalogService;
import com.chatbot.catalog.CatalogSyncService;
import com.chatbot.catalog.Quote;
import com.chatbot.catalog.QuoteRequest;
import com.chatbot.catalog.QuoteRequest.Line;
import com.chatbot.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Los importes esperados son los que muestra la web para los mismos datos. */
class CatalogTests extends IntegrationTest {

    @Autowired
    private CatalogSyncService sync;

    @Autowired
    private CatalogService catalog;

    @Test
    void syncCopiesProductsOptionsAndPrices() {
        GrenlusFixture.stubCatalog(META, GrenlusFixture.remera(14900), GrenlusFixture.TAZA_INACTIVA, GrenlusFixture.TAZA);

        assertThat(sync.sync()).hasValue(3);

        assertThat(count("SELECT count(*) FROM products WHERE active")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM products WHERE NOT active AND external_id = '7'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM product_options WHERE option_type = 'SIZE' AND attributes->>'special' = 'true'"))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT public_url FROM products WHERE external_id = '1'", String.class))
                .isEqualTo("https://grenlus.pages.dev/producto/1");
    }

    @Test
    void singleShirtWithSmallPrintMatchesTheWebsite() {
        Quote.Ok quote = quoteOk(remeraId(14900), "CHICA", List.of(new Line(1, "M", "Negro")));

        assertThat(quote.total()).isEqualByComparingTo("16900");
        assertThat(quote.wholesaleDiscountApplied()).isFalse();
    }

    @Test
    void wholesaleDiscountAppliesOnlyToBasePriceAndSpecialSizesAddTheirSurcharge() {
        UUID remera = remeraId(14900);

        Quote.Ok quote = quoteOk(remera, "GRANDE", List.of(new Line(3, "M", null), new Line(3, "T6", null)));

        // Base 14.900 con 10 % = 13.410; estampa grande 6.000; talle especial 2.500.
        assertThat(quote.lines().get(0).unitPrice()).isEqualByComparingTo("19410");
        assertThat(quote.lines().get(1).unitPrice()).isEqualByComparingTo("21910");
        assertThat(quote.total()).isEqualByComparingTo("123960");
        assertThat(quote.wholesaleDiscountApplied()).isTrue();
    }

    @Test
    void colorWithSurchargeIsAdded() {
        Quote.Ok quote = quoteOk(remeraId(14900), "CHICA", List.of(new Line(1, "M", "dorado")));

        assertThat(quote.total()).isEqualByComparingTo("18400");
    }

    @Test
    void productWithoutSizesIsQuotedWithIncludedPrint() {
        GrenlusFixture.stubCatalog(META, GrenlusFixture.TAZA);
        sync.sync();
        UUID taza = jdbc.queryForObject("SELECT id FROM products WHERE external_id = '8'", UUID.class);

        Quote.Ok quote = quoteOk(taza, "CHICA", List.of(new Line(2, null, null)));

        assertThat(quote.total()).isEqualByComparingTo("13000");
    }

    @Test
    void missingOrInvalidDataIsReportedInsteadOfGuessed() {
        UUID remera = remeraId(14900);

        assertThat(quote(remera, null, List.of(new Line(1, "M", null))))
                .isInstanceOfSatisfying(Quote.Invalid.class, invalid -> assertThat(invalid.reason()).contains("CHICA"));
        assertThat(quote(remera, "CHICA", List.of(new Line(1, "XXXL", null))))
                .isInstanceOfSatisfying(Quote.Invalid.class, invalid -> assertThat(invalid.reason()).contains("Talle"));
        assertThat(quote(remera, "CHICA", List.of(new Line(0, "M", null)))).isInstanceOf(Quote.Invalid.class);
    }

    @Test
    void priceChangeKeepsHistoryAndAppliesNewPrice() {
        UUID remera = remeraId(14900);
        GrenlusFixture.stubCatalog(META, GrenlusFixture.remera(16000));

        sync.sync();

        assertThat(count("SELECT count(*) FROM price_tiers WHERE product_id = ? AND valid_to IS NOT NULL", remera))
                .isPositive();
        assertThat(quoteOk(remera, "CHICA", List.of(new Line(1, "M", null))).total()).isEqualByComparingTo("18000");
    }

    @Test
    void unchangedCatalogDoesNotDuplicatePrices() {
        UUID remera = remeraId(14900);
        int tiers = count("SELECT count(*) FROM price_tiers WHERE product_id = ?", remera);

        sync.sync();

        assertThat(count("SELECT count(*) FROM price_tiers WHERE product_id = ?", remera)).isEqualTo(tiers);
    }

    @Test
    void productRemovedFromWebsiteIsDeactivated() {
        remeraId(14900);
        GrenlusFixture.stubCatalog(META, GrenlusFixture.TAZA);

        sync.sync();

        assertThat(count("SELECT count(*) FROM products WHERE external_id = '1' AND active")).isZero();
    }

    @Test
    void searchFindsPluralAndAccentsAndPricesAreCurrent() {
        remeraId(14900);

        TenantContext.runAs(tenantId, () -> {
            assertThat(catalog.pricesAreCurrent()).isTrue();
            assertThat(catalog.findProducts("REMERAS")).extracting("name").containsExactly("Remera Personalizada");
            assertThat(catalog.findProducts("remera").getFirst().wholesaleBasePrice())
                    .isEqualByComparingTo(new BigDecimal("13410"));
        });
    }

    private UUID remeraId(int basePrice) {
        GrenlusFixture.stubCatalog(META, GrenlusFixture.remera(basePrice));
        sync.sync();
        return jdbc.queryForObject("SELECT id FROM products WHERE external_id = '1'", UUID.class);
    }

    private Quote quote(UUID productId, String designSize, List<Line> lines) {
        return TenantContext.callAs(tenantId, () -> catalog.quote(new QuoteRequest(productId, designSize, lines)));
    }

    private Quote.Ok quoteOk(UUID productId, String designSize, List<Line> lines) {
        Quote quote = quote(productId, designSize, lines);
        assertThat(quote).isInstanceOf(Quote.Ok.class);
        return (Quote.Ok) quote;
    }
}
