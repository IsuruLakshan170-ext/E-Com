-- Product photos. Holds a same-origin path served by the frontend (e.g. /products/yoga-mat.jpg), never an external
-- URL: both CSPs (SecurityConfig, frontend nginx) allow img-src 'self' only. The CHECK mirrors the DTO's pattern so
-- a protocol-relative "//host/..." or a ".." segment can't get in by any write path.

ALTER TABLE products ADD COLUMN image_url VARCHAR(300);

ALTER TABLE products ADD CONSTRAINT chk_products_image_url
    CHECK (image_url IS NULL OR (image_url ~ '^/[A-Za-z0-9_./-]{1,299}$'
                                 AND image_url NOT LIKE '//%'
                                 AND image_url NOT LIKE '%..%'));
