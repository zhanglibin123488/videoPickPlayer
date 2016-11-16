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
package com.google.android.exoplayer2.source.dash.manifest;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.drm.DrmInitData;
import com.google.android.exoplayer2.drm.DrmInitData.SchemeData;
import com.google.android.exoplayer2.extractor.mp4.PsshAtomUtil;
import com.google.android.exoplayer2.source.dash.manifest.SegmentBase.SegmentList;
import com.google.android.exoplayer2.source.dash.manifest.SegmentBase.SegmentTemplate;
import com.google.android.exoplayer2.source.dash.manifest.SegmentBase.SegmentTimelineElement;
import com.google.android.exoplayer2.source.dash.manifest.SegmentBase.SingleSegmentBase;
import com.google.android.exoplayer2.upstream.ParsingLoadable;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.UriUtil;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.util.XmlPullParserUtil;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.xml.sax.helpers.DefaultHandler;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

/**
 * A parser of media presentation description files.
 */
public class DashManifestParser extends DefaultHandler
    implements ParsingLoadable.Parser<DashManifest> {

  private static final String TAG = "MpdParser";

  private static final Pattern FRAME_RATE_PATTERN = Pattern.compile("(\\d+)(?:/(\\d+))?");

  private final String contentId;
  private final XmlPullParserFactory xmlParserFactory;

  /**
   * Equivalent to calling {@code new DashManifestParser(null)}.
   */
  public DashManifestParser() {
    this(null);
  }

  /**
   * @param contentId An optional content identifier to include in the parsed manifest.
   */
  public DashManifestParser(String contentId) {
    this.contentId = contentId;
    try {
      xmlParserFactory = XmlPullParserFactory.newInstance();
    } catch (XmlPullParserException e) {
      throw new RuntimeException("Couldn't create XmlPullParserFactory instance", e);
    }
  }

  // MPD parsing.

  @Override
  public DashManifest parse(Uri uri, InputStream inputStream) throws IOException {
    try {
      XmlPullParser xpp = xmlParserFactory.newPullParser();
      xpp.setInput(inputStream, null);
      int eventType = xpp.next();
      if (eventType != XmlPullParser.START_TAG || !"MPD".equals(xpp.getName())) {
        throw new ParserException(
            "inputStream does not contain a valid media presentation description");
      }
      return parseMediaPresentationDescription(xpp, uri.toString());
    } catch (XmlPullParserException | ParseException e) {
      throw new ParserException(e);
    }
  }

  protected DashManifest parseMediaPresentationDescription(XmlPullParser xpp,
      String baseUrl) throws XmlPullParserException, IOException, ParseException {
    long availabilityStartTime = parseDateTime(xpp, "availabilityStartTime", C.TIME_UNSET);
    long durationMs = parseDuration(xpp, "mediaPresentationDuration", C.TIME_UNSET);
    long minBufferTimeMs = parseDuration(xpp, "minBufferTime", C.TIME_UNSET);
    String typeString = xpp.getAttributeValue(null, "type");
    boolean dynamic = typeString != null && typeString.equals("dynamic");
    long minUpdateTimeMs = dynamic ? parseDuration(xpp, "minimumUpdatePeriod", C.TIME_UNSET)
        : C.TIME_UNSET;
    long timeShiftBufferDepthMs = dynamic
        ? parseDuration(xpp, "timeShiftBufferDepth", C.TIME_UNSET) : C.TIME_UNSET;
    long suggestedPresentationDelayMs = dynamic
        ? parseDuration(xpp, "suggestedPresentationDelay", C.TIME_UNSET) : C.TIME_UNSET;
    UtcTimingElement utcTiming = null;
    Uri location = null;

    List<Period> periods = new ArrayList<>();
    long nextPeriodStartMs = dynamic ? C.TIME_UNSET : 0;
    boolean seenEarlyAccessPeriod = false;
    boolean seenFirstBaseUrl = false;
    do {
      xpp.next();
      if (XmlPullParserUtil.isStartTag(xpp, "BaseURL")) {
        if (!seenFirstBaseUrl) {
          baseUrl = parseBaseUrl(xpp, baseUrl);
          seenFirstBaseUrl = true;
        }
      } else if (XmlPullParserUtil.isStartTag(xpp, "UTCTiming")) {
        utcTiming = parseUtcTiming(xpp);
      } else if (XmlPullParserUtil.isStartTag(xpp, "Location")) {
        location = Uri.parse(xpp.nextText());
      } else if (XmlPullParserUtil.isStartTag(xpp, "Period") && !seenEarlyAccessPeriod) {
        Pair<Period, Long> periodWithDurationMs = parsePeriod(xpp, baseUrl, nextPeriodStartMs);
        Period period = periodWithDurationMs.first;
        if (period.startMs == C.TIME_UNSET) {
          if (dynamic) {
            // This is an early access period. Ignore it. All subsequent periods must also be
            // early access.
            seenEarlyAccessPeriod = true;
          } else {
            throw new ParserException("Unable to determine start of period " + periods.size());
          }
        } else {
          long periodDurationMs = periodWithDurationMs.second;
          nextPeriodStartMs = periodDurationMs == C.TIME_UNSET ? C.TIME_UNSET
              : (period.startMs + periodDurationMs);
          periods.add(period);
        }
      }
    } while (!XmlPullParserUtil.isEndTag(xpp, "MPD"));

    if (durationMs == C.TIME_UNSET) {
      if (nextPeriodStartMs != C.TIME_UNSET) {
        // If we know the end time of the final period, we can use it as the duration.
        durationMs = nextPeriodStartMs;
      } else if (!dynamic) {
        throw new ParserException("Unable to determine duration of static manifest.");
      }
    }

    if (periods.isEmpty()) {
      throw new ParserException("No periods found.");
    }

    return buildMediaPresentationDescription(availabilityStartTime, durationMs, minBufferTimeMs,
        dynamic, minUpdateTimeMs, timeShiftBufferDepthMs, suggestedPresentationDelayMs, utcTiming,
        location, periods);
  }

  protected DashManifest buildMediaPresentationDescription(long availabilityStartTime,
      long durationMs, long minBufferTimeMs, boolean dynamic, long minUpdateTimeMs,
      long timeShiftBufferDepthMs, long suggestedPresentationDelayMs, UtcTimingElement utcTiming,
      Uri location, List<Period> periods) {
    return new DashManifest(availabilityStartTime, durationMs, minBufferTimeMs,
        dynamic, minUpdateTimeMs, timeShiftBufferDepthMs, suggestedPresentationDelayMs, utcTiming,
        location, periods);
  }

  protected UtcTimingElement parseUtcTiming(XmlPullParser xpp) {
    String schemeIdUri = xpp.getAttributeValue(null, "schemeIdUri");
    String value = xpp.getAttributeValue(null, "value");
    return buildUtcTimingElement(schemeIdUri, value);
  }

  protected UtcTimingElement buildUtcTimingElement(String schemeIdUri, String value) {
    return new UtcTimingElement(schemeIdUri, value);
  }

  protected Pair<Period, Long> parsePeriod(XmlPullParser xpp, String baseUrl, long defaultStartMs)
      throws XmlPullParserException, IOException {
    String id = xpp.getAttributeValue(null, "id");
    long startMs = parseDuration(xpp, "start", defaultStartMs);
    long durationMs = parseDuration(xpp, "duration", C.TIME_UNSET);
    SegmentBase segmentBase = null;
    List<AdaptationSet> adaptationSets = new ArrayList<>();
    boolean seenFirstBaseUrl = false;
    do {
      xpp.next();
      if (XmlPullParserUtil.isStartTag(xpp, "BaseURL")) {
        if (!seenFirstBaseUrl) {
          baseUrl = parseBaseUrl(xpp, baseUrl);
          seenFirstBaseUrl = true;
        }
      } else if (XmlPullParserUtil.isStartTag(xpp, "AdaptationSet")) {
        adaptationSets.add(parseAdaptationSet(xpp, baseUrl, segmentBase));
      } else if (XmlPullParserUtil.isStartTag(xpp, "SegmentBase")) {
        segmentBase = parseSegmentBase(xpp, baseUrl, null);
      } else if (XmlPullParserUtil.isStartTag(xpp, "SegmentList")) {
        segmentBase = parseSegmentList(xpp, baseUrl, null);
      } else if (XmlPullParserUtil.isStartTag(xpp, "SegmentTemplate")) {
        segmentBase = parseSegmentTemplate(xpp, baseUrl, null);
      }
    } while (!XmlPullParserUtil.isEndTag(xpp, "Period"));

    return Pair.create(buildPeriod(id, startMs, adaptationSets), durationMs);
  }

  protected Period buildPeriod(String id, long startMs, List<AdaptationSet> adaptationSets) {
    return new Period(id, startMs, adaptationSets);
  }

  // AdaptationSet parsing.

  protected AdaptationSet parseAdaptationSet(XmlPullParser xpp, String baseUrl,
      SegmentBase segmentBase) throws XmlPullParserException, IOException {
    int id = parseInt(xpp, "id", AdaptationSet.UNSET_ID);
    int contentType = parseContentType(xpp);

    String mimeType = xpp.getAttributeValue(null, "mimeType");
    String codecs = xpp.getAttributeValue(null, "codecs");
    int width = parseInt(xpp, "width", Format.NO_VALUE);
    int height = parseInt(xpp, "height", Format.NO_VALUE);
    float frameRate = parseFrameRate(xpp, Format.NO_VALUE);
    int audioChannels = Format.NO_VALUE;
    int audioSamplingRate = parseInt(xpp, "audioSamplingRate", Format.NO_VALUE);
    String language = xpp.getAttributeValue(null, "lang");
    ArrayList<SchemeData> drmSchemeDatas = new ArrayList<>();
    List<RepresentationInfo> representationInfos = new ArrayList<>();

    boolean seenFirstBaseUrl = false;
    do {
      xpp.next();
      if (XmlPullParserUtil.isStartTag(xpp, "BaseURL")) {
        if (!seenFirstBaseUrl) {
          baseUrl = parseBaseUrl(xpp, baseUrl);
          seenFirstBaseUrl = true;
        }
      } else if (XmlPullParserUtil.isStartTag(xpp, "ContentProtection")) {
        SchemeData contentProtection = parseContentProtection(xpp);
        if (contentProtection != null) {
          drmSchemeDatas.add(contentProtection);
        }
      } else if (XmlPullParserUtil.isStartTag(xpp, "ContentComponent")) {
        language = checkLanguageConsistency(language, xpp.getAttributeValue(null, "lang"));
        contentType = checkContentTypeConsistency(contentType, parseContentType(xpp));
      } else if (XmlPullParserUtil.isStartTag(xpp, "Representation")) {
        RepresentationInfo representationInfo = parseRepresentation(xpp, baseUrl, mimeType, codecs,
            width, height, frameRate, audioChannels, audioSamplingRate, language, segmentBase);
        contentType = checkContentTypeConsistency(contentType,
            getContentType(representationInfo.format));
        representationInfos.add(representationInfo);
      } else if (XmlPullParserUtil.isStartTag(xpp, "AudioChannelConfiguration")) {
        audioChannels = parseAudioChannelConfiguration(xpp);
      } else if (XmlPullParserUtil.isStartTag(xpp, "SegmentBase")) {
        segmentBase = parseSegmentBase(xpp, baseUrl, (SingleSegmentBase) segmentBase);
      } else if (XmlPullParserUtil.isStartTag(xpp, "SegmentList")) {
        segmentBase = parseSegmentList(xpp, baseUrl, (SegmentList) segmentBase);
      } else if (XmlPullParserUtil.isStartTag(xpp, "SegmentTemplate")) {
        segmentBase = parseSegmentTemplate(xpp, baseUrl, (SegmentTemplate) segmentBase);
      } else if (XmlPullParserUtil.isStartTag(xpp)) {
        parseAdaptationSetChild(xpp);
      }
    } while (!XmlPullParserUtil.isEndTag(xpp, "AdaptationSet"));

    List<Representation> representations = new ArrayList<>(representationInfos.size());
    for (int i = 0; i < representationInfos.size(); i++) {
      representations.add(buildRepresentation(representationInfos.get(i), contentId,
          drmSchemeDatas));
    }

    return buildAdaptationSet(id, contentType, representations);
  }

  protected AdaptationSet buildAdaptationSet(int id, int contentType,
      List<Representation> representations) {
    return new AdaptationSet(id, contentType, representations);
  }

  protected int parseContentType(XmlPullParser xpp) {
    String contentType = xpp.getAttributeValue(null, "contentType");
    return TextUtils.isEmpty(contentType) ? C.TRACK_TYPE_UNKNOWN
        : MimeTypes.BASE_TYPE_AUDIO.equals(contentType) ? C.TRACK_TYPE_AUDIO
        : MimeTypes.BASE_TYPE_VIDEO.equals(contentType) ? C.TRACK_TYPE_VIDEO
        : MimeTypes.BASE_TYPE_TEXT.equals(contentType) ? C.TRACK_TYPE_TEXT
        : C.TRACK_TYPE_UNKNOWN;
  }

  protected int getContentType(Format format) {
    String sampleMimeType = format.sampleMimeType;
    if (TextUtils.isEmpty(sampleMimeType)) {
      return C.TRACK_TYPE_UNKNOWN;
    } else if (MimeTypes.isVideo(sampleMimeType)) {
      return C.TRACK_TYPE_VIDEO;
    } else if (MimeTypes.isAudio(sampleMimeType)) {
      return C.TRACK_TYPE_AUDIO;
    } else if (mimeTypeIsRawText(sampleMimeType)
        || MimeTypes.APPLICATION_RAWCC.equals(format.containerMimeType)) {
      return C.TRACK_TYPE_TEXT;
    }
    return C.TRACK_TYPE_UNKNOWN;
  }

  /**
   * Parses a ContentProtection element.
   *
   * @param xpp The parser from which to read.
   * @throws XmlPullParserException If an error occurs parsing the element.
   * @throws IOException If an error occurs reading the element.
   * @return {@link SchemeData} parsed from the ContentProtection element, or null if the element is
   *     unsupported.
   */
  protected SchemeData parseContentProtection(XmlPullParser xpp) throws XmlPullParserException,
      IOException {
    byte[] data = null;
    UUID uuid = null;
    boolean seenPsshElement = false;
    boolean requiresSecureDecoder = false;
    do {
      xpp.next();
      // The cenc:pssh element is defined in 23001-7:2015.
      if (XmlPullParserUtil.isStartTag(xpp, "cenc:pssh") && xpp.next() == XmlPullParser.TEXT) {
        seenPsshElement = true;
        data = Base64.decode(xpp.getText(), Base64.DEFAULT);
        uuid = PsshAtomUtil.parseUuid(data);
      } else if (XmlPullParserUtil.isStartTag(xpp, "widevine:license")) {
        String robustnessLevel = xpp.getAttributeValue(null, "robustness_level");
        requiresSecureDecoder = robustnessLevel != null && robustnessLevel.startsWith("HW");
      }
    } while (!XmlPullParserUtil.isEndTag(xpp, "ContentProtection"));
    if (!seenPsshElement) {
      return null;
    } else if (uuid != null) {
      return new SchemeData(uuid, MimeTypes.VIDEO_MP4, data, requiresSecureDecoder);
    } else {
      Log.w(TAG, "Skipped unsupported ContentProtection element");
      return null;
    }
  }

  /**
   * Parses children of AdaptationSet elements not specifically parsed elsewhere.
   *
   * @param xpp The XmpPullParser from which the AdaptationSet child should be parsed.
   * @throws XmlPullParserException If an error occurs parsing the element.
   * @throws IOException If an error occurs reading the element.
   */
  protected void parseAdaptationSetChild(XmlPullParser xpp)
      throws XmlPullParserException, IOException {
    // pass
  }

  // Representation parsing.

  protected RepresentationInfo parseRepresentation(XmlPullParser xpp, String baseUrl,
      String adaptationSetMimeType, String adaptationSetCodecs, int adaptationSetWidth,
      int adaptationSetHeight, float adaptationSetFrameRate, int adaptationSetAudioChannels,
      int adaptationSetAudioSamplingRate, String adaptationSetLanguage, SegmentBase segmentBase)
      throws XmlPullParserException, IOException {
    String id = xpp.getAttributeValue(null, "id");
    int bandwidth = parseInt(xpp, "bandwidth", Format.NO_VALUE);

    String mimeType = parseString(xpp, "mimeType", adaptationSetMimeType);
    String codecs = parseString(xpp, "codecs", adaptationSetCodecs);
    int width = parseInt(xpp, "width", adaptationSetWidth);
    int height = parseInt(xpp, "height", adaptationSetHeight);
    float frameRate = parseFrameRate(xpp, adaptationSetFrameRate);
    int audioChannels = adaptationSetAudioChannels;
    int audioSamplingRate = parseInt(xpp, "audioSamplingRate", adaptationSetAudioSamplingRate);
    ArrayList<SchemeData> drmSchemeDatas = new ArrayList<>();

    boolean seenFirstBaseUrl = false;
    do {
      xpp.next();
      if (XmlPullParserUtil.isStartTag(xpp, "BaseURL")) {
        if (!seenFirstBaseUrl) {
          baseUrl = parseBaseUrl(xpp, baseUrl);
          seenFirstBaseUrl = true;
        }
      } else if (XmlPullParserUtil.isStartTag(xpp, "AudioChannelConfiguration")) {
        audioChannels = parseAudioChannelConfiguration(xpp);
      } else if (XmlPullParserUtil.isStartTag(xpp, "SegmentBase")) {
        segmentBase = parseSegmentBase(xpp, baseUrl, (SingleSegmentBase) segmentBase);
      } else if (XmlPullParserUtil.isStartTag(xpp, "SegmentList")) {
        segmentBase = parseSegmentList(xpp, baseUrl, (SegmentList) segmentBase);
      } else if (XmlPullParserUtil.isStartTag(xpp, "SegmentTemplate")) {
        segmentBase = parseSegmentTemplate(xpp, baseUrl, (SegmentTemplate) segmentBase);
      } else if (XmlPullParserUtil.isStartTag(xpp, "ContentProtection")) {
        SchemeData contentProtection = parseContentProtection(xpp);
        if (contentProtection != null) {
          drmSchemeDatas.add(contentProtection);
        }
      }
    } while (!XmlPullParserUtil.isEndTag(xpp, "Representation"));

    Format format = buildFormat(id, mimeType, width, height, frameRate, audioChannels,
        audioSamplingRate, bandwidth, adaptationSetLanguage, codecs);
    segmentBase = segmentBase != null ? segmentBase : new SingleSegmentBase(baseUrl);

    return new RepresentationInfo(format, segmentBase, drmSchemeDatas);
  }

  protected Format buildFormat(String id, String containerMimeType, int width, int height,
      float frameRate, int audioChannels, int audioSamplingRate, int bitrate, String language,
      String codecs) {
    String sampleMimeType = getSampleMimeType(containerMimeType, codecs);
    if (sampleMimeType != null) {
      if (MimeTypes.isVideo(sampleMimeType)) {
        return Format.createVideoContainerFormat(id, containerMimeType, sampleMimeType, codecs,
            bitrate, width, height, frameRate, null);
      } else if (MimeTypes.isAudio(sampleMimeType)) {
        return Format.createAudioContainerFormat(id, containerMimeType, sampleMimeType, codecs,
            bitrate, audioChannels, audioSamplingRate, null, 0, language);
      } else if (mimeTypeIsRawText(sampleMimeType)) {
        return Format.createTextContainerFormat(id, containerMimeType, sampleMimeType, codecs,
            bitrate, 0, language);
      } else {
        return Format.createContainerFormat(id, containerMimeType, codecs, sampleMimeType, bitrate);
      }
    } else {
      return Format.createContainerFormat(id, containerMimeType, codecs, sampleMimeType, bitrate);
    }
  }

  protected Representation buildRepresentation(RepresentationInfo representationInfo,
      String contentId, ArrayList<SchemeData> extraDrmSchemeDatas) {
    Format format = representationInfo.format;
    ArrayList<SchemeData> drmSchemeDatas = representationInfo.drmSchemeDatas;
    drmSchemeDatas.addAll(extraDrmSchemeDatas);
    if (!drmSchemeDatas.isEmpty()) {
      format = format.copyWithDrmInitData(new DrmInitData(drmSchemeDatas));
    }
    return Representation.newInstance(contentId, Representation.REVISION_ID_DEFAULT, format,
        representationInfo.segmentBase);
  }

  // SegmentBase, SegmentList and SegmentTemplate parsing.

  protected SingleSegmentBase parseSegmentBase(XmlPullParser xpp, String baseUrl,
      SingleSegmentBase parent) throws XmlPullParserException, IOException {

    long timescale = parseLong(xpp, "timescale", parent != null ? parent.timescale : 1);
    long presentationTimeOffset = parseLong(xpp, "presentationTimeOffset",
        parent != null ? parent.presentationTimeOffset : 0);

    long indexStart = parent != null ? parent.indexStart : 0;
    long indexLength = parent != null ? parent.indexLength : 0;
    String indexRangeText = xpp.getAttributeValue(null, "indexRange");
    if (indexRangeText != null) {
      String[] indexRange = indexRangeText.split("-");
      indexStart = Long.parseLong(indexRange[0]);
      indexLength = Long.parseLong(indexRange[1]) - indexStart + 1;
    }

    RangedUri initialization = parent != null ? parent.initialization : null;
    do {
      xpp.next();
      if (XmlPullParserUtil.isStartTag(xpp, "Initialization")) {
        initialization = parseInitialization(xpp, baseUrl);
      }
    } while (!XmlPullParserUtil.isEndTag(xpp, "SegmentBase"));

    return buildSingleSegmentBase(initialization, timescale, presentationTimeOffset, baseUrl,
        indexStart, indexLength);
  }

  protected SingleSegmentBase buildSingleSegmentBase(RangedUri initialization, long timescale,
      long presentationTimeOffset, String baseUrl, long indexStart, long indexLength) {
    return new SingleSegmentBase(initialization, timescale, presentationTimeOffset, baseUrl,
        indexStart, indexLength);
  }

  protected SegmentList parseSegmentList(XmlPullParser xpp, String baseUrl, SegmentList parent)
      throws XmlPullParserException, IOException {

    long timescale = parseLong(xpp, "timescale", parent != null ? parent.timescale : 1);
    long presentationTimeOffset = parseLong(xpp, "presentationTimeOffset",
        parent != null ? parent.presentationTimeOffset : 0);
    long duration = parseLong(xpp, "duration", parent != null ? parent.duration : C.TIME_UNSET);
    int startNumber = parseInt(xpp, "startNumber", parent != null ? parent.startNumber : 1);

    RangedUri initialization = null;
    List<SegmentTimelineElement> timeline = null;
    List<RangedUri> segments = null;

    do {
      xpp.next();
      if (XmlPullParserUtil.isStartTag(xpp, "Initialization")) {
        initialization = parseInitialization(xpp, baseUrl);
      } else if (XmlPullParserUtil.isStartTag(xpp, "SegmentTimeline")) {
        timeline = parseSegmentTimeline(xpp);
      } else if (XmlPullParserUtil.isStartTag(xpp, "SegmentURL")) {
        if (segments == null) {
          segments = new ArrayList<>();
        }
        segments.add(parseSegmentUrl(xpp, baseUrl));
      }
    } while (!XmlPullParserUtil.isEndTag(xpp, "SegmentList"));

    if (parent != null) {
      initialization = initialization != null ? initialization : parent.initialization;
      timeline = timeline != null ? timeline : parent.segmentTimeline;
      segments = segments != null ? segments : parent.mediaSegments;
    }

    return buildSegmentList(initialization, timescale, presentationTimeOffset,
        startNumber, duration, timeline, segments);
  }

  protected SegmentList buildSegmentList(RangedUri initialization, long timescale,
      long presentationTimeOffset, int startNumber, long duration,
      List<SegmentTimelineElement> timeline, List<RangedUri> segments) {
    return new SegmentList(initialization, timescale, presentationTimeOffset,
        startNumber, duration, timeline, segments);
  }

  protected SegmentTemplate parseSegmentTemplate(XmlPullParser xpp, String baseUrl,
      SegmentTemplate parent) throws XmlPullParserException, IOException {
    long timescale = parseLong(xpp, "timescale", parent != null ? parent.timescale : 1);
    long presentationTimeOffset = parseLong(xpp, "presentationTimeOffset",
        parent != null ? parent.presentationTimeOffset : 0);
    long duration = parseLong(xpp, "duration", parent != null ? parent.duration : C.TIME_UNSET);
    int startNumber = parseInt(xpp, "startNumber", parent != null ? parent.startNumber : 1);
    UrlTemplate mediaTemplate = parseUrlTemplate(xpp, "media",
        parent != null ? parent.mediaTemplate : null);
    UrlTemplate initializationTemplate = parseUrlTemplate(xpp, "initialization",
        parent != null ? parent.initializationTemplate : null);

    RangedUri initialization = null;
    List<SegmentTimelineElement> timeline = null;

    do {
      xpp.next();
      if (XmlPullParserUtil.isStartTag(xpp, "Initialization")) {
        initialization = parseInitialization(xpp, baseUrl);
      } else if (XmlPullParserUtil.isStartTag(xpp, "SegmentTimeline")) {
        timeline = parseSegmentTimeline(xpp);
      }
    } while (!XmlPullParserUtil.isEndTag(xpp, "SegmentTemplate"));

    if (parent != null) {
      initialization = initialization != null ? initialization : parent.initialization;
      timeline = timeline != null ? timeline : parent.segmentTimeline;
    }

    return buildSegmentTemplate(initialization, timescale, presentationTimeOffset,
        startNumber, duration, timeline, initializationTemplate, mediaTemplate, baseUrl);
  }

  protected SegmentTemplate buildSegmentTemplate(RangedUri initialization, long timescale,
      long presentationTimeOffset, int startNumber, long duration,
      List<SegmentTimelineElement> timeline, UrlTemplate initializationTemplate,
      UrlTemplate mediaTemplate, String baseUrl) {
    return new SegmentTemplate(initialization, timescale, presentationTimeOffset,
        startNumber, duration, timeline, initializationTemplate, mediaTemplate, baseUrl);
  }

  protected List<SegmentTimelineElement> parseSegmentTimeline(XmlPullParser xpp)
      throws XmlPullParserException, IOException {
    List<SegmentTimelineElement> segmentTimeline = new ArrayList<>();
    long elapsedTime = 0;
    do {
      xpp.next();
      if (XmlPullParserUtil.isStartTag(xpp, "S")) {
        elapsedTime = parseLong(xpp, "t", elapsedTime);
        long duration = parseLong(xpp, "d", C.TIME_UNSET);
        int count = 1 + parseInt(xpp, "r", 0);
        for (int i = 0; i < count; i++) {
          segmentTimeline.add(buildSegmentTimelineElement(elapsedTime, duration));
          elapsedTime += duration;
        }
      }
    } while (!XmlPullParserUtil.isEndTag(xpp, "SegmentTimeline"));
    return segmentTimeline;
  }

  protected SegmentTimelineElement buildSegmentTimelineElement(long elapsedTime, long duration) {
    return new SegmentTimelineElement(elapsedTime, duration);
  }

  protected UrlTemplate parseUrlTemplate(XmlPullParser xpp, String name,
      UrlTemplate defaultValue) {
    String valueString = xpp.getAttributeValue(null, name);
    if (valueString != null) {
      return UrlTemplate.compile(valueString);
    }
    return defaultValue;
  }

  protected RangedUri parseInitialization(XmlPullParser xpp, String baseUrl) {
    return parseRangedUrl(xpp, baseUrl, "sourceURL", "range");
  }

  protected RangedUri parseSegmentUrl(XmlPullParser xpp, String baseUrl) {
    return parseRangedUrl(xpp, baseUrl, "media", "mediaRange");
  }

  protected RangedUri parseRangedUrl(XmlPullParser xpp, String baseUrl, String urlAttribute,
      String rangeAttribute) {
    String urlText = xpp.getAttributeValue(null, urlAttribute);
    long rangeStart = 0;
    long rangeLength = C.LENGTH_UNSET;
    String rangeText = xpp.getAttributeValue(null, rangeAttribute);
    if (rangeText != null) {
      String[] rangeTextArray = rangeText.split("-");
      rangeStart = Long.parseLong(rangeTextArray[0]);
      if (rangeTextArray.length == 2) {
        rangeLength = Long.parseLong(rangeTextArray[1]) - rangeStart + 1;
      }
    }
    return buildRangedUri(baseUrl, urlText, rangeStart, rangeLength);
  }

  protected RangedUri buildRangedUri(String baseUrl, String urlText, long rangeStart,
      long rangeLength) {
    return new RangedUri(baseUrl, urlText, rangeStart, rangeLength);
  }

  // AudioChannelConfiguration parsing.

  protected int parseAudioChannelConfiguration(XmlPullParser xpp)
      throws XmlPullParserException, IOException {
    String schemeIdUri = parseString(xpp, "schemeIdUri", null);
    int audioChannels = "urn:mpeg:dash:23003:3:audio_channel_configuration:2011".equals(schemeIdUri)
        ? parseInt(xpp, "value", Format.NO_VALUE) : Format.NO_VALUE;
    do {
      xpp.next();
    } while (!XmlPullParserUtil.isEndTag(xpp, "AudioChannelConfiguration"));
    return audioChannels;
  }

  // Utility methods.

  /**
   * Derives a sample mimeType from a container mimeType and codecs attribute.
   *
   * @param containerMimeType The mimeType of the container.
   * @param codecs The codecs attribute.
   * @return The derived sample mimeType, or null if it could not be derived.
   */
  private static String getSampleMimeType(String containerMimeType, String codecs) {
    if (MimeTypes.isAudio(containerMimeType)) {
      return MimeTypes.getAudioMediaMimeType(codecs);
    } else if (MimeTypes.isVideo(containerMimeType)) {
      return MimeTypes.getVideoMediaMimeType(codecs);
    } else if (MimeTypes.APPLICATION_RAWCC.equals(containerMimeType)) {
      if (codecs != null) {
        if (codecs.contains("cea708")) {
          return MimeTypes.APPLICATION_CEA708;
        } else if (codecs.contains("eia608") || codecs.contains("cea608")) {
          return MimeTypes.APPLICATION_CEA608;
        }
      }
      return null;
    } else if (mimeTypeIsRawText(containerMimeType)) {
      return containerMimeType;
    } else if (MimeTypes.APPLICATION_MP4.equals(containerMimeType)) {
      if ("stpp".equals(codecs)) {
        return MimeTypes.APPLICATION_TTML;
      } else if ("wvtt".equals(codecs)) {
        return MimeTypes.APPLICATION_MP4VTT;
      }
    }
    return null;
  }

  /**
   * Returns whether a mimeType is a text sample mimeType.
   *
   * @param mimeType The mimeType.
   * @return Whether the mimeType is a text sample mimeType.
   */
  private static boolean mimeTypeIsRawText(String mimeType) {
    return MimeTypes.isText(mimeType) || MimeTypes.APPLICATION_TTML.equals(mimeType);
  }

  /**
   * Checks two languages for consistency, returning the consistent language, or throwing an
   * {@link IllegalStateException} if the languages are inconsistent.
   * <p>
   * Two languages are consistent if they are equal, or if one is null.
   *
   * @param firstLanguage The first language.
   * @param secondLanguage The second language.
   * @return The consistent language.
   */
  private static String checkLanguageConsistency(String firstLanguage, String secondLanguage) {
    if (firstLanguage == null) {
      return secondLanguage;
    } else if (secondLanguage == null) {
      return firstLanguage;
    } else {
      Assertions.checkState(firstLanguage.equals(secondLanguage));
      return firstLanguage;
    }
  }

  /**
   * Checks two adaptation set content types for consistency, returning the consistent type, or
   * throwing an {@link IllegalStateException} if the types are inconsistent.
   * <p>
   * Two types are consistent if they are equal, or if one is {@link C#TRACK_TYPE_UNKNOWN}.
   * Where one of the types is {@link C#TRACK_TYPE_UNKNOWN}, the other is returned.
   *
   * @param firstType The first type.
   * @param secondType The second type.
   * @return The consistent type.
   */
  private static int checkContentTypeConsistency(int firstType, int secondType) {
    if (firstType == C.TRACK_TYPE_UNKNOWN) {
      return secondType;
    } else if (secondType == C.TRACK_TYPE_UNKNOWN) {
      return firstType;
    } else {
      Assertions.checkState(firstType == secondType);
      return firstType;
    }
  }

  protected static float parseFrameRate(XmlPullParser xpp, float defaultValue) {
    float frameRate = defaultValue;
    String frameRateAttribute = xpp.getAttributeValue(null, "frameRate");
    if (frameRateAttribute != null) {
      Matcher frameRateMatcher = FRAME_RATE_PATTERN.matcher(frameRateAttribute);
      if (frameRateMatcher.matches()) {
        int numerator = Integer.parseInt(frameRateMatcher.group(1));
        String denominatorString = frameRateMatcher.group(2);
        if (!TextUtils.isEmpty(denominatorString)) {
          frameRate = (float) numerator / Integer.parseInt(denominatorString);
        } else {
          frameRate = numerator;
        }
      }
    }
    return frameRate;
  }

  protected static long parseDuration(XmlPullParser xpp, String name, long defaultValue) {
    String value = xpp.getAttributeValue(null, name);
    if (value == null) {
      return defaultValue;
    } else {
      return Util.parseXsDuration(value);
    }
  }

  protected static long parseDateTime(XmlPullParser xpp, String name, long defaultValue)
      throws ParseException {
    String value = xpp.getAttributeValue(null, name);
    if (value == null) {
      return defaultValue;
    } else {
      return Util.parseXsDateTime(value);
    }
  }

  protected static String parseBaseUrl(XmlPullParser xpp, String parentBaseUrl)
      throws XmlPullParserException, IOException {
    xpp.next();
    return UriUtil.resolve(parentBaseUrl, xpp.getText());
  }

  protected static int parseInt(XmlPullParser xpp, String name, int defaultValue) {
    String value = xpp.getAttributeValue(null, name);
    return value == null ? defaultValue : Integer.parseInt(value);
  }

  protected static long parseLong(XmlPullParser xpp, String name, long defaultValue) {
    String value = xpp.getAttributeValue(null, name);
    return value == null ? defaultValue : Long.parseLong(value);
  }

  protected static String parseString(XmlPullParser xpp, String name, String defaultValue) {
    String value = xpp.getAttributeValue(null, name);
    return value == null ? defaultValue : value;
  }

  private static final class RepresentationInfo {

    public final Format format;
    public final SegmentBase segmentBase;
    public final ArrayList<SchemeData> drmSchemeDatas;

    public RepresentationInfo(Format format, SegmentBase segmentBase,
        ArrayList<SchemeData> drmSchemeDatas) {
      this.format = format;
      this.segmentBase = segmentBase;
      this.drmSchemeDatas = drmSchemeDatas;
    }

  }

}
