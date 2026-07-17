package com.aktagon.llmkit;

/**
 * A reference to an uploaded file attached to a text-generation request
 * (ADR-060). {@code id} addresses an OpenAI/Anthropic uploaded file;
 * {@code uri}/{@code mimeType} address a Google {@code file_data}. Mirror of
 * Swift's {@code FileRef} / the fields Rust's {@code File} carries.
 *
 * @param id the uploaded-file id (OpenAI/Anthropic)
 * @param uri the file URI (Google {@code file_data.file_uri})
 * @param mimeType the file MIME type (Google {@code file_data.mime_type})
 */
record FileRef(String id, String uri, String mimeType) {}
