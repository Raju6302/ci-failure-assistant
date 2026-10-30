/** Turns "Hello, World!" into "hello-world". */
export function slugify(title) {
  return title.trim().toLowerCase().replace(/[^a-z0-9]+/, '-').replace(/^-|-$/g, '');
}
