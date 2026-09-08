/**
 * ImageUtils.js
 * Utilities for handling avatar and profile picture URLs across Android and Web.
 */

/**
 * Safely formats an avatar string so that it can be directly used in <img src="..." />.
 *
 * Android stores raw Base64 JPEG strings (e.g. "/9j/4AAQSkZJRg...") in Firestore without
 * the "data:image/jpeg;base64," URI prefix.
 *
 * When browsers encounter <img src="/9j/4AAQ..." />, they treat the leading "/" as a root-relative
 * path on the website (e.g. https://lksdialerweb.pages.dev/9j/4AAQ...), causing:
 *   1. Massive HTTP GET requests sent to the server.
 *   2. HTTP 414 (URI Too Long) errors.
 *   3. Failed image rendering, falling back to initials.
 *
 * This function guarantees any raw Base64 string is correctly prefixed as a Data URI.
 */
export function formatAvatarUrl(url) {
  if (!url || typeof url !== 'string') return null;
  const trimmed = url.trim();
  if (!trimmed) return null;

  // Already a valid Data URI, HTTP/HTTPS URL, or Blob URL
  if (
    trimmed.startsWith('data:') ||
    trimmed.startsWith('http://') ||
    trimmed.startsWith('https://') ||
    trimmed.startsWith('blob:')
  ) {
    return trimmed;
  }

  // Handle JPEG base64 (standard JPEG base64 starts with "/9j/" or "9j/")
  if (trimmed.startsWith('/9j/')) {
    return `data:image/jpeg;base64,${trimmed}`;
  }
  if (trimmed.startsWith('9j/')) {
    return `data:image/jpeg;base64,/${trimmed}`;
  }

  // Handle PNG base64 (starts with "iVBORw" or "/iVBORw")
  if (trimmed.startsWith('iVBORw')) {
    return `data:image/png;base64,${trimmed}`;
  }
  if (trimmed.startsWith('/iVBORw')) {
    return `data:image/png;base64,${trimmed.substring(1)}`;
  }

  // Handle WebP base64 (starts with "UklGR")
  if (trimmed.startsWith('UklGR')) {
    return `data:image/webp;base64,${trimmed}`;
  }

  // If it's a long base64 string (> 50 chars) without scheme
  if (trimmed.length > 50) {
    return `data:image/jpeg;base64,${trimmed}`;
  }

  return trimmed;
}
