-- Fix V8's chk_products_image_url: Postgres caps a regex repetition count at 255, so '{1,299}' made the CHECK raise
-- "invalid regular expression" on every non-null write (surfacing as a 409). Same rule, with the length bound moved
-- out of the regex (the column is already VARCHAR(300)). V8 is not edited (checksum).

ALTER TABLE products DROP CONSTRAINT chk_products_image_url;

ALTER TABLE products ADD CONSTRAINT chk_products_image_url
    CHECK (image_url IS NULL OR (image_url ~ '^/[A-Za-z0-9_./-]+$'
                                 AND image_url NOT LIKE '//%'
                                 AND image_url NOT LIKE '%..%'));
