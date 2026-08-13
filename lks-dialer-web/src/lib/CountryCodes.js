export const allCountries = [
  { name: "India", flag: "🇮🇳", dialCode: "+91", code: "IN" },
  { name: "Bangladesh", flag: "🇧🇩", dialCode: "+880", code: "BD" },
  { name: "United States", flag: "🇺🇸", dialCode: "+1", code: "US" },
  { name: "United Kingdom", flag: "🇬🇧", dialCode: "+44", code: "GB" },
  { name: "Pakistan", flag: "🇵🇰", dialCode: "+92", code: "PK" },
  { name: "United Arab Emirates", flag: "🇦🇪", dialCode: "+971", code: "AE" },
  { name: "Saudi Arabia", flag: "🇸🇦", dialCode: "+966", code: "SA" },
  { name: "Canada", flag: "🇨🇦", dialCode: "+1", code: "CA" },
  { name: "Malaysia", flag: "🇲🇾", dialCode: "+60", code: "MY" },
  { name: "Singapore", flag: "🇸🇬", dialCode: "+65", code: "SG" },
  { name: "Australia", flag: "🇦🇺", dialCode: "+61", code: "AU" },
  { name: "Germany", flag: "🇩🇪", dialCode: "+49", code: "DE" },
  { name: "France", flag: "🇫🇷", dialCode: "+33", code: "FR" },
  { name: "Italy", flag: "🇮🇹", dialCode: "+39", code: "IT" },
  { name: "Qatar", flag: "🇶🇦", dialCode: "+974", code: "QA" },
  { name: "Kuwait", flag: "🇰🇼", dialCode: "+965", code: "KW" },
  { name: "Oman", flag: "🇴🇲", dialCode: "+968", code: "OM" },
  { name: "Bahrain", flag: "🇧🇭", dialCode: "+973", code: "BH" },
  { name: "Nepal", flag: "🇳🇵", dialCode: "+977", code: "NP" },
  { name: "Sri Lanka", flag: "🇱🇰", dialCode: "+94", code: "LK" }
];

export const defaultCountry = allCountries[0];

export const formatPhoneNumber = (dialCode, number) => {
  const cleanNumber = number.replace(/[^0-9]/g, "");
  return `${dialCode}${cleanNumber}`;
};
