package com.ecom.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class ShippingAddress {

    @Column(name = "ship_recipient_name", nullable = false, length = 100)
    private String recipientName;

    @Column(name = "ship_line1", nullable = false, length = 200)
    private String line1;

    @Column(name = "ship_line2", length = 200)
    private String line2;

    @Column(name = "ship_city", nullable = false, length = 100)
    private String city;

    @Column(name = "ship_region", length = 100)
    private String region;

    @Column(name = "ship_postal_code", nullable = false, length = 20)
    private String postalCode;

    @Column(name = "ship_country_code", nullable = false, length = 2)
    private String countryCode;

    protected ShippingAddress() {
    }

    public ShippingAddress(String recipientName, String line1, String line2, String city, String region,
                           String postalCode, String countryCode) {
        this.recipientName = recipientName;
        this.line1 = line1;
        this.line2 = line2;
        this.city = city;
        this.region = region;
        this.postalCode = postalCode;
        this.countryCode = countryCode;
    }

    public String getRecipientName() { return recipientName; }
    public String getLine1() { return line1; }
    public String getLine2() { return line2; }
    public String getCity() { return city; }
    public String getRegion() { return region; }
    public String getPostalCode() { return postalCode; }
    public String getCountryCode() { return countryCode; }
}
