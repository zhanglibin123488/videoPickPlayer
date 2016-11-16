/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.metadata;

/**
 * Decodes metadata from binary data.
 *
 * @param <T> The type of the metadata.
 */
public interface MetadataDecoder<T> {

  /**
   * Checks whether the decoder supports a given mime type.
   *
   * @param mimeType A metadata mime type.
   * @return Whether the mime type is supported.
   */
  boolean canDecode(String mimeType);

  /**
   * Decodes a metadata object from the provided binary data.
   *
   * @param data The raw binary data from which to decode the metadata.
   * @param size The size of the input data.
   * @return The decoded metadata object.
   * @throws MetadataDecoderException If a problem occurred decoding the data.
   */
  T decode(byte[] data, int size) throws MetadataDecoderException;

}
