import type { ShippingAddress } from '../../api/types';
import { COUNTRY_PATTERN } from '../../lib/validation';
import { Field, FormRow } from '../ui/Field';

export interface AddressDraft {
  recipientName: string;
  line1: string;
  line2: string;
  city: string;
  region: string;
  postalCode: string;
  countryCode: string;
}

export const EMPTY_ADDRESS: AddressDraft = { recipientName: '', line1: '', line2: '', city: '', region: '', postalCode: '', countryCode: '' };

export function addressProblems(a: AddressDraft): Partial<Record<keyof AddressDraft, string>> {
  const problems: Partial<Record<keyof AddressDraft, string>> = {};
  if (!a.recipientName.trim()) problems.recipientName = 'Required';
  if (!a.line1.trim()) problems.line1 = 'Required';
  if (!a.city.trim()) problems.city = 'Required';
  if (!a.postalCode.trim()) problems.postalCode = 'Required';
  if (!COUNTRY_PATTERN.test(a.countryCode)) problems.countryCode = 'Two capital letters, for example US or LK';
  return problems;
}

export function toShippingAddress(a: AddressDraft): ShippingAddress {
  return {
    recipientName: a.recipientName.trim(),
    line1: a.line1.trim(),
    line2: a.line2.trim() || null,
    city: a.city.trim(),
    region: a.region.trim() || null,
    postalCode: a.postalCode.trim(),
    countryCode: a.countryCode,
  };
}

interface AddressFormProps {
  value: AddressDraft;
  onChange: (value: AddressDraft) => void;
  problems: Partial<Record<keyof AddressDraft, string>>;
  disabled?: boolean;
}

export function AddressForm({ value, onChange, problems, disabled }: AddressFormProps) {
  const set = (key: keyof AddressDraft) => (event: { target: { value: string } }) =>
    onChange({ ...value, [key]: key === 'countryCode' ? event.target.value.toUpperCase().slice(0, 2) : event.target.value });
  return (
    <fieldset className="fieldset" disabled={disabled}>
      <legend>Shipping address</legend>
      <Field label="Full name" required error={problems.recipientName}>
        <input value={value.recipientName} maxLength={100} autoComplete="name" onChange={set('recipientName')} />
      </Field>
      <Field label="Address line 1" required error={problems.line1}>
        <input value={value.line1} maxLength={200} autoComplete="address-line1" onChange={set('line1')} />
      </Field>
      <Field label="Address line 2" error={problems.line2}>
        <input value={value.line2} maxLength={200} autoComplete="address-line2" onChange={set('line2')} />
      </Field>
      <FormRow>
        <Field label="City" required error={problems.city}>
          <input value={value.city} maxLength={100} autoComplete="address-level2" onChange={set('city')} />
        </Field>
        <Field label="Region / state" error={problems.region}>
          <input value={value.region} maxLength={100} autoComplete="address-level1" onChange={set('region')} />
        </Field>
      </FormRow>
      <FormRow>
        <Field label="Postal code" required error={problems.postalCode}>
          <input value={value.postalCode} maxLength={20} autoComplete="postal-code" onChange={set('postalCode')} />
        </Field>
        <Field label="Country code" required error={problems.countryCode} hint="ISO code, e.g. US">
          <input value={value.countryCode} maxLength={2} autoComplete="country" onChange={set('countryCode')} />
        </Field>
      </FormRow>
    </fieldset>
  );
}
