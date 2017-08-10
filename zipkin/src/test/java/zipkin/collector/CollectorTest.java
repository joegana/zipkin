/**
 * Copyright 2015-2017 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.collector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.Test;
import zipkin.Codec;
import zipkin.Span;
import zipkin.internal.ApplyTimestampAndDuration;
import zipkin.internal.Span2Codec;
import zipkin.internal.Span2Converter;
import zipkin.storage.Callback;
import zipkin.storage.InMemoryStorage;
import zipkin.storage.QueryRequest;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static zipkin.TestObjects.LOTS_OF_SPANS;
import static zipkin.TestObjects.span;
import static zipkin.storage.Callback.NOOP;

public class CollectorTest {
  List<String> messages = new ArrayList<>();

  Collector collector = new Collector.Builder(new Logger("", null) {
    @Override
    public void log(Level level, String msg, Throwable thrown) {
      assertThat(level).isEqualTo(Level.WARNING);
      messages.add(msg);
    }
  }).storage(new InMemoryStorage()).build();

  Span span1 = ApplyTimestampAndDuration.apply(LOTS_OF_SPANS[0]);
  Span span2 = ApplyTimestampAndDuration.apply(LOTS_OF_SPANS[1]);

  @Test
  public void acceptSpansCallback_toStringIncludesSpanIds() {
    assertThat(collector.acceptSpansCallback(asList(span1, span2)))
      .hasToString("AcceptSpans([" + span1.idString() + ", " + span2.idString() + "])");
  }

  @Test
  public void acceptSpansCallback_onErrorWithNullMessage() {
    Callback<Void> callback = collector.acceptSpansCallback(asList(span1));
    callback.onError(new RuntimeException());

    assertThat(messages)
      .containsExactly("Cannot store spans [" + span1.idString() + "] due to RuntimeException()");
  }

  @Test
  public void acceptSpansCallback_onErrorWithMessage() {
    Callback<Void> callback = collector.acceptSpansCallback(asList(span1));
    callback.onError(new IllegalArgumentException("no beer"));

    assertThat(messages)
      .containsExactly(
        "Cannot store spans [" + span1.idString() + "] due to IllegalArgumentException(no beer)");
  }

  @Test
  public void errorAcceptingSpans_onErrorWithNullMessage() {
    String message =
        collector.errorStoringSpans(asList(span1), new RuntimeException()).getMessage();

    assertThat(messages)
        .containsExactly(message)
        .containsExactly("Cannot store spans [" + span1.idString() + "] due to RuntimeException()");
  }

  @Test
  public void errorAcceptingSpans_onErrorWithMessage() {
    String message =
        collector.errorStoringSpans(asList(span1), new IllegalArgumentException("no beer"))
            .getMessage();

    assertThat(messages)
        .containsExactly(message)
      .containsExactly(
        "Cannot store spans [" + span1.idString() + "] due to IllegalArgumentException(no beer)");
  }

  @Test
  public void errorDecoding_onErrorWithNullMessage() {
    String message = collector.errorReading(new RuntimeException()).getMessage();

    assertThat(messages)
        .containsExactly(message)
        .containsExactly("Cannot decode spans due to RuntimeException()");
  }

  @Test
  public void errorDecoding_onErrorWithMessage() {
    String message =
        collector.errorReading(new IllegalArgumentException("no beer")).getMessage();

    assertThat(messages)
        .containsExactly(message)
        .containsExactly("Cannot decode spans due to IllegalArgumentException(no beer)");
  }

  @Test
  public void errorDecoding_doesntWrapMalformedException() {
    String message =
        collector.errorReading(new IllegalArgumentException("Malformed reading spans")).getMessage();

    assertThat(messages)
        .containsExactly(message)
        .containsExactly("Malformed reading spans");
  }

  @Test
  public void acceptSpans_detectsThrift() {
    collector.acceptSpans(Codec.THRIFT.writeSpan(span1), NOOP);

    assertThat(collector.storage.spanStore().getTraces(QueryRequest.builder().build()))
      .hasSize(1);
  }

  @Test
  public void acceptSpans_detectsThriftList() {
    collector.acceptSpans(Codec.THRIFT.writeSpans(asList(span1, span2)), NOOP);

    assertThat(collector.storage.spanStore().getTraces(QueryRequest.builder().build()))
      .hasSize(2);
  }

  @Test
  public void acceptSpans_detectsJsonList() {
    collector.acceptSpans(Codec.JSON.writeSpans(asList(span1, span2)), NOOP);

    assertThat(collector.storage.spanStore().getTraces(QueryRequest.builder().build()))
      .hasSize(2);
  }

  @Test
  public void acceptSpans_detectsJson2List() {
    byte[] bytes = Span2Codec.JSON.writeSpans(Arrays.asList(
      Span2Converter.fromSpan(span1).get(0),
      Span2Converter.fromSpan(span2).get(0)
    ));
    collector.acceptSpans(bytes, NOOP);

    assertThat(collector.storage.spanStore().getTraces(QueryRequest.builder().build()))
      .hasSize(2);
  }

  @Test
  public void unsampledSpansArentStored() {
    collector = Collector.builder(Collector.class)
        .sampler(CollectorSampler.create(0f))
        .storage(new InMemoryStorage()).build();

    collector.accept(asList(span(Long.MIN_VALUE)), NOOP);

    assertThat(collector.storage.spanStore().getServiceNames()).isEmpty();
  }

  @Test
  public void debugFlagWins() {
    collector.accept(asList(span(Long.MIN_VALUE).toBuilder().debug(true).build()), NOOP);

    assertThat(collector.storage.spanStore().getServiceNames()).containsExactly("service");
  }
}
