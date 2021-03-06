/*
   Copyright (c) 2017 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.restli.common;

import com.linkedin.data.codec.DataCodec;
import com.linkedin.data.codec.JacksonDataCodec;
import com.linkedin.data.codec.PsonDataCodec;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.activation.MimeType;
import javax.activation.MimeTypeParseException;


/**
 * Rest.Li representation of supported content types. Each content type is associated with a CODEC that will be used
 * to serialize/de-serialize the content.
 *
 * @author Karthik Balasubramanian
 */
public class ContentType
{
  private static final JacksonDataCodec JACKSON_DATA_CODEC = new JacksonDataCodec();
  private static final PsonDataCodec PSON_DATA_CODEC = new PsonDataCodec();

  public static final ContentType PSON =
      new ContentType(RestConstants.HEADER_VALUE_APPLICATION_PSON, PSON_DATA_CODEC);
  public static final ContentType JSON =
      new ContentType(RestConstants.HEADER_VALUE_APPLICATION_JSON, JACKSON_DATA_CODEC);
  // Content type to be used only as an accept type.
  public static final ContentType
      ACCEPT_TYPE_ANY = new ContentType(RestConstants.HEADER_VALUE_ACCEPT_ANY, JACKSON_DATA_CODEC);

  private static final Map<String, ContentType> SUPPORTED_TYPES = new ConcurrentHashMap<>();
  static
  {
    // Include content types supported by Rest.Li by default.
    SUPPORTED_TYPES.put(PSON.getHeaderKey(), PSON);
    SUPPORTED_TYPES.put(JSON.getHeaderKey(), JSON);
  }

  /**
   * Helper method to create a custom content type and also register it as a supported type.
   * @param headerKey Content-Type header value to associate this content type with.
   * @param codec Codec to use for this content type.
   *
   * @return A ContentType representing this custom type that can be use with restli framework.
   */
  public static ContentType createContentType(String headerKey, DataCodec codec)
  {
    assert headerKey != null : "Header key for custom content type cannot be null";
    assert codec != null : "Codec for custom content type cannot be null";
    ContentType customType = new ContentType(headerKey, codec);
    SUPPORTED_TYPES.put(headerKey.toLowerCase(), customType);
    return customType;
  }

  /**
   * Get content type based on the given mime type
   * @param contentTypeHeaderValue value of Content-Type header.
   * @return type of content Restli supports. Can be null if the Content-Type header does not match any of the supported
   * content types.
   *
   * @throws MimeTypeParseException thrown when content type is not parsable.
   */
  public static Optional<ContentType> getContentType(String contentTypeHeaderValue) throws MimeTypeParseException
  {
    if (contentTypeHeaderValue == null)
    {
      return Optional.of(JSON);
    }
    MimeType parsedMimeType = new MimeType(contentTypeHeaderValue);
    return Optional.ofNullable(SUPPORTED_TYPES.get(parsedMimeType.getBaseType().toLowerCase()));
  }

  private final String _headerKey;
  private final DataCodec _codec;

  /** Constructable only through {@link ContentType#createContentType(String, DataCodec)} */
  private ContentType(String headerKey, DataCodec codec)
  {
    _headerKey = headerKey;
    _codec = codec;
  }

  public String getHeaderKey()
  {
    return _headerKey;
  }

  public DataCodec getCodec()
  {
    return _codec;
  }
}
