package local.bilimp4.app;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.DummyTrackOutput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.mp4.Mp4Extractor;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.Arrays;

@OptIn(markerClass = UnstableApi.class)
final class Mp4Track implements AutoCloseable, ExtractorOutput {
    private final RandomAccessFile file;
    private final int type;
    private final long length;
    private final Extractor extractor;
    private final PositionHolder seek = new PositionHolder();
    private final TrackOutput discarded = new DummyTrackOutput();
    private final SampleOutput output = new SampleOutput();
    private DefaultExtractorInput input;
    private Integer trackId;
    private long durationUs = C.TIME_UNSET;
    private boolean ended;

    record Sample(long timeUs, int flags, byte[] data) {}

    static Mp4Track open(File path, int type) throws IOException {
        RandomAccessFile source = new RandomAccessFile(path, "r");
        try {
            return new Mp4Track(source, type);
        } catch (IOException | RuntimeException error) {
            source.close();
            throw error;
        }
    }

    private Mp4Track(RandomAccessFile file, int type) throws IOException {
        this.file = file;
        this.type = type;
        length = file.length();
        input = new DefaultExtractorInput(file::read, 0, length);
        Extractor fragmented = new FragmentedMp4Extractor();
        boolean isFragmented = fragmented.sniff(input);
        input.resetPeekPosition();
        if (isFragmented) {
            extractor = fragmented;
        } else {
            fragmented.release();
            Extractor regular = new Mp4Extractor();
            boolean isMp4 = regular.sniff(input);
            input.resetPeekPosition();
            if (!isMp4) {
                regular.release();
                throw new IOException("无法读取所选文件。请确认缓存下载完整。");
            }
            extractor = regular;
        }
        extractor.init(this);
    }

    Sample next() throws IOException {
        while (output.samples.isEmpty() && !ended) {
            int status = extractor.read(input, seek);
            if (status == Extractor.RESULT_SEEK) {
                file.seek(seek.position);
                input = new DefaultExtractorInput(file::read, seek.position, length);
            } else if (status == Extractor.RESULT_END_OF_INPUT) {
                ended = true;
            }
        }
        return output.samples.poll();
    }

    Format format() {
        return output.format;
    }

    long durationUs() {
        return durationUs;
    }

    @Override
    public TrackOutput track(int id, int type) {
        if (this.type != type) return discarded;
        if (trackId == null) trackId = id;
        return trackId == id ? output : discarded;
    }

    @Override
    public void endTracks() {}

    @Override
    public void seekMap(SeekMap map) {
        durationUs = map.getDurationUs();
    }

    @Override
    public void close() throws IOException {
        try {
            extractor.release();
        } finally {
            file.close();
        }
    }

    private static final class SampleOutput implements TrackOutput {
        private final ArrayDeque<Sample> samples = new ArrayDeque<>();
        private byte[] buffer = new byte[128 * 1024];
        private int length;
        private Format format;

        @Override
        public void format(Format format) {
            this.format = format;
        }

        @Override
        public int sampleData(DataReader input, int count, boolean allowEnd, int part) throws IOException {
            reserve(count);
            int bytes = input.read(buffer, length, count);
            if (bytes == C.RESULT_END_OF_INPUT) {
                if (!allowEnd) throw new EOFException("缓存文件读取不完整。");
                return bytes;
            }
            length += bytes;
            return bytes;
        }

        @Override
        public void sampleData(ParsableByteArray data, int count, int part) {
            reserve(count);
            data.readBytes(buffer, length, count);
            length += count;
        }

        private void reserve(int count) {
            if (count < 0 || (long) length + count > 32 * 1024 * 1024) {
                throw new IllegalArgumentException("单帧数据过大。");
            }
            int required = length + count;
            if (required > buffer.length) buffer = Arrays.copyOf(buffer, Math.max(required, buffer.length * 2));
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData crypto) {
            if (crypto != null || (flags & C.BUFFER_FLAG_ENCRYPTED) != 0) {
                throw new IllegalArgumentException("缓存包含加密轨道。");
            }
            int end = length - offset;
            int start = end - size;
            if (start < 0 || offset < 0 || size < 0) throw new IllegalArgumentException("缓存数据不完整。");
            samples.add(new Sample(timeUs, flags & C.BUFFER_FLAG_KEY_FRAME, Arrays.copyOfRange(buffer, start, end)));
            System.arraycopy(buffer, end, buffer, 0, offset);
            length = offset;
        }
    }
}
