package com.aktagon.llmkit;

/**
 * A vision-input image attached to a text-generation request (ADR-060). The
 * builder's {@code .image(mimeType, data)} lowers bytes into this carrier as a
 * base64 data URI; the transform emits it as the provider's native image
 * block. Mirror of Swift's {@code InputImage} / Rust's {@code InputImage}.
 *
 * @param url a {@code data:<mime>;base64,<data>} URI (or a plain URL, unused
 *     by the current builder surface)
 * @param mimeType the image MIME type (e.g. {@code "image/png"})
 * @param detail the OpenAI vision detail level; empty defaults to {@code "auto"}
 */
record InputImage(String url, String mimeType, String detail) {}
