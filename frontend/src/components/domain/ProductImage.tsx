import { useState } from 'react';
import type { Product } from '../../api/types';

/** Product photo, or the first-letter tile when there is no image or it fails to load. */
export function ProductImage({ product, className }: { product: Pick<Product, 'name' | 'imageUrl'>; className: string }) {
  const [broken, setBroken] = useState(false);
  if (product.imageUrl && !broken) {
    return (
      <div className={`${className} has-image`}>
        <img src={product.imageUrl} alt={product.name} loading="lazy" onError={() => setBroken(true)} />
      </div>
    );
  }
  return (
    <div className={className} aria-hidden="true">
      {product.name.charAt(0).toUpperCase()}
    </div>
  );
}
