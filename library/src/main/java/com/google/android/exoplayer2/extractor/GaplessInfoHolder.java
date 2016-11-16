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
package com.google.android.exoplayer2.extractor;

import com.google.android.exoplayer2.Format;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holder for gapless playback information.
 */
public final class GaplessInfoHolder {

  private static final String GAPLESS_COMMENT_ID = "iTunSMPB";
  private static final Pattern GAPLESS_COMMENT_PATTERN =
      Pattern.compile("^ [0-9a-fA-F]{8} ([0-9a-fA-F]{8}) ([0-9a-fA-F]{8})");

  /**
   * The number of samples to trim from the start of the decoded audio stream, or
   * {@link Format#NO_VALUE} if not set.
   */
  public int encoderDelay;

  /**
   * The number of samples to trim from the end of the decoded audio stream, or
   * {@link Format#NO_VALUE} if not set.
   */
  public int encoderPadding;

  /**
   * Creates a new holder for gapless playback information.
   */
  public GaplessInfoHolder() {
    encoderDelay = Format.NO_VALUE;
    encoderPadding = Format.NO_VALUE;
  }

  /**
   * Populates the holder with data from an MP3 Xing header, if valid and non-zero.
   *
   * @param value The 24-bit value to decode.
   * @return Whether the holder was populated.
   */
  public boolean setFromXingHeaderValue(int value) {
    int encoderDelay = value >> 12;
    int encoderPadding = value & 0x0FFF;
    if (encoderDelay > 0 || encoderPadding > 0) {
      this.encoderDelay = encoderDelay;
      this.encoderPadding = encoderPadding;
      return true;
    }
    return false;
  }

  /**
   * Populates the holder with data parsed from a gapless playback comment (stored in an ID3 header
   * or MPEG 4 user data), if valid and non-zero.
   *
   * @param name The comment's identifier.
   * @param data The comment's payload data.
   * @return Whether the holder was populated.
   */
  public boolean setFromComment(String name, String data) {
    if (!GAPLESS_COMMENT_ID.equals(name)) {
      return false;
    }
    Matcher matcher = GAPLESS_COMMENT_PATTERN.matcher(data);
    if (matcher.find()) {
      try {
        int encoderDelay = Integer.parseInt(matcher.group(1), 16);
        int encoderPadding = Integer.parseInt(matcher.group(2), 16);
        if (encoderDelay > 0 || encoderPadding > 0) {
          this.encoderDelay = encoderDelay;
          this.encoderPadding = encoderPadding;
          return true;
        }
      } catch (NumberFormatException e) {
        // Ignore incorrectly formatted comments.
      }
    }
    return false;
  }

  /**
   * Returns whether {@link #encoderDelay} and {@link #encoderPadding} have been set.
   */
  public boolean hasGaplessInfo() {
    return encoderDelay != Format.NO_VALUE && encoderPadding != Format.NO_VALUE;
  }

}
