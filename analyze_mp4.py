#!/usr/bin/env python3
"""Analyze MP4 H.264 NAL structure: avcC, stts, stsz, stss, and first mdat samples."""

import struct
import sys

FILE = r"C:\Users\holmes\Projects\hawkeye_wifi_viewer\test_video.mp4"


def read_u8(f):
    return struct.unpack(">B", f.read(1))[0]

def read_u16(f):
    return struct.unpack(">H", f.read(2))[0]

def read_u32(f):
    return struct.unpack(">I", f.read(4))[0]

def read_u64(f):
    return struct.unpack(">Q", f.read(8))[0]


def find_boxes(f, start, end, target_types=None):
    """Yield (box_type, box_start, box_size, data_start) within [start, end)."""
    f.seek(start)
    while f.tell() < end:
        box_start = f.tell()
        if box_start + 8 > end:
            break
        size = read_u32(f)
        box_type = f.read(4).decode("ascii", errors="replace")
        data_start = box_start + 8
        if size == 1:
            size = read_u64(f)
            data_start = box_start + 16
        elif size == 0:
            size = end - box_start
        if target_types is None or box_type in target_types:
            yield box_type, box_start, size, data_start
        next_pos = box_start + size
        if next_pos <= f.tell():
            break
        f.seek(next_pos)


def find_box_recursive(f, start, end, path):
    """Find a box by path like ['moov', 'trak', 'mdia', 'minf', 'stbl', 'stts']."""
    if not path:
        return start, end
    target = path[0]
    for box_type, box_start, box_size, data_start in find_boxes(f, start, end):
        if box_type == target:
            # Container boxes: moov, trak, mdia, minf, stbl, edts
            if target in ("moov", "trak", "mdia", "minf", "stbl", "edts", "udta"):
                return find_box_recursive(f, data_start, box_start + box_size, path[1:])
            else:
                return data_start, box_start + box_size
    return None, None


def find_video_trak(f, file_size):
    """Find the video track's trak box boundaries."""
    for moov_type, moov_start, moov_size, moov_data in find_boxes(f, 0, file_size, ["moov"]):
        for trak_type, trak_start, trak_size, trak_data in find_boxes(f, moov_data, moov_start + moov_size, ["trak"]):
            # Check if this trak has a video handler (hdlr with 'vide')
            mdia_start, mdia_end = find_box_recursive(f, trak_data, trak_start + trak_size, ["mdia"])
            if mdia_start is None:
                continue
            for hdlr_type, hdlr_start, hdlr_size, hdlr_data in find_boxes(f, mdia_start, mdia_end, ["hdlr"]):
                f.seek(hdlr_data)
                _version_flags = f.read(4)
                _pre_defined = f.read(4)
                handler_type = f.read(4).decode("ascii", errors="replace")
                if handler_type == "vide":
                    return trak_data, trak_start + trak_size
    return None, None


def parse_avcc(f, file_size):
    """Find and parse avcC box."""
    print("=" * 70)
    print("1. avcC (AVC Decoder Configuration Record)")
    print("=" * 70)

    trak_start, trak_end = find_video_trak(f, file_size)
    if trak_start is None:
        print("  ERROR: No video trak found")
        return

    # Navigate: trak -> mdia -> minf -> stbl -> stsd -> avc1 -> avcC
    stbl_start, stbl_end = find_box_recursive(f, trak_start, trak_end, ["mdia", "minf", "stbl"])
    if stbl_start is None:
        print("  ERROR: No stbl found")
        return

    # Find stsd
    for stsd_type, stsd_start, stsd_size, stsd_data in find_boxes(f, stbl_start, stbl_end, ["stsd"]):
        f.seek(stsd_data)
        _version_flags = f.read(4)
        entry_count = read_u32(f)
        print(f"  stsd entry count: {entry_count}")

        # Read avc1/avc3 sample entry
        se_start = f.tell()
        se_size = read_u32(f)
        se_type = f.read(4).decode("ascii", errors="replace")
        print(f"  Sample entry type: {se_type}, size: {se_size}")

        # Skip reserved(6) + data_ref_index(2) + predefined(2) + reserved(2) + predefined(12)
        # + width(2) + height(2) + hres(4) + vres(4) + reserved(4) + frame_count(2)
        # + compressor_name(32) + depth(2) + pre_defined(2)
        f.seek(se_start + 8 + 6 + 2)  # past size+type+reserved+data_ref_index
        f.read(2 + 2 + 12)  # pre_defined + reserved + pre_defined
        width = read_u16(f)
        height = read_u16(f)
        hres = read_u32(f)
        vres = read_u32(f)
        f.read(4)  # reserved
        frame_count = read_u16(f)
        compressor = f.read(32)
        depth = read_u16(f)
        pre_def = read_u16(f)  # should be -1 (0xFFFF)

        print(f"  Video: {width}x{height}, frame_count={frame_count}, depth={depth}")

        # Now find avcC box in the remaining data of the sample entry
        avcc_search_start = f.tell()
        avcc_search_end = se_start + se_size

        for avcc_type, avcc_start, avcc_size, avcc_data in find_boxes(f, avcc_search_start, avcc_search_end):
            if avcc_type == "avcC":
                f.seek(avcc_data)
                config_version = read_u8(f)
                profile_idc = read_u8(f)
                profile_compat = read_u8(f)
                level_idc = read_u8(f)
                length_size_minus1 = read_u8(f) & 0x03
                nal_length_size = length_size_minus1 + 1

                print(f"\n  avcC found at offset 0x{avcc_start:X}")
                print(f"  configurationVersion: {config_version}")
                print(f"  profile_idc: {profile_idc} (0x{profile_idc:02X})")
                print(f"  profile_compatibility: 0x{profile_compat:02X}")
                print(f"  level_idc: {level_idc} (Level {level_idc/10:.1f})")
                print(f"  lengthSizeMinusOne: {length_size_minus1} (NAL length size: {nal_length_size} bytes)")

                # SPS
                num_sps_byte = read_u8(f)
                num_sps = num_sps_byte & 0x1F
                print(f"\n  SPS count: {num_sps}")
                for i in range(num_sps):
                    sps_len = read_u16(f)
                    sps_data = f.read(sps_len)
                    nal_type = sps_data[0] & 0x1F if sps_data else -1
                    print(f"    SPS[{i}]: length={sps_len}, NAL type={nal_type}")
                    print(f"      Hex: {sps_data.hex()}")
                    # Parse SPS basics
                    if sps_data and nal_type == 7:
                        print(f"      forbidden_zero_bit={sps_data[0] >> 7}, nal_ref_idc={( sps_data[0] >> 5) & 0x03}")
                        print(f"      profile_idc={sps_data[1]}, constraint_set_flags=0x{sps_data[2]:02X}, level_idc={sps_data[3]}")

                # PPS
                num_pps = read_u8(f)
                print(f"\n  PPS count: {num_pps}")
                for i in range(num_pps):
                    pps_len = read_u16(f)
                    pps_data = f.read(pps_len)
                    nal_type = pps_data[0] & 0x1F if pps_data else -1
                    print(f"    PPS[{i}]: length={pps_len}, NAL type={nal_type}")
                    print(f"      Hex: {pps_data.hex()}")

                return nal_length_size

    print("  ERROR: avcC not found")
    return 4  # default


def parse_stts(f, file_size):
    """Find and parse stts (sample-to-time) box."""
    print("\n" + "=" * 70)
    print("2. stts (Sample-to-Time / Decoding Time-to-Sample)")
    print("=" * 70)

    trak_start, trak_end = find_video_trak(f, file_size)
    stbl_start, stbl_end = find_box_recursive(f, trak_start, trak_end, ["mdia", "minf", "stbl"])

    # Also get timescale from mdhd
    mdhd_start, mdhd_end = find_box_recursive(f, trak_start, trak_end, ["mdia", "mdhd"])
    timescale = 0
    if mdhd_start:
        f.seek(mdhd_start)
        version = read_u8(f)
        f.read(3)  # flags
        if version == 1:
            f.read(8 + 8)  # creation, modification
            timescale = read_u32(f)
            duration = read_u64(f)
        else:
            f.read(4 + 4)  # creation, modification
            timescale = read_u32(f)
            duration = read_u32(f)
        print(f"  Media timescale: {timescale} ticks/sec, duration: {duration} ticks ({duration/timescale:.3f}s)")

    for stts_type, stts_start, stts_size, stts_data in find_boxes(f, stbl_start, stbl_end, ["stts"]):
        f.seek(stts_data)
        version = read_u8(f)
        f.read(3)  # flags
        entry_count = read_u32(f)
        print(f"  Entry count: {entry_count}")

        sample_idx = 0
        for i in range(entry_count):
            sample_count = read_u32(f)
            sample_delta = read_u32(f)
            duration_ms = (sample_delta / timescale * 1000) if timescale else 0
            fps = timescale / sample_delta if sample_delta else 0
            print(f"    Entry[{i}]: count={sample_count}, delta={sample_delta} ticks "
                  f"({duration_ms:.2f}ms per sample, ~{fps:.2f} fps)")

            # Show first 10 individual samples
            for j in range(min(sample_count, max(0, 10 - sample_idx))):
                print(f"      Sample {sample_idx}: delta={sample_delta} ticks ({duration_ms:.2f}ms)")
                sample_idx += 1
            if sample_count > (10 - (sample_idx - min(sample_count, max(0, 10 - (sample_idx - sample_count))))):
                remaining = sample_count - min(sample_count, 10)
                if remaining > 0 and sample_idx < sample_count:
                    pass  # already printed what we need
        break


def parse_stsz(f, file_size):
    """Find and parse stsz (sample size) box."""
    print("\n" + "=" * 70)
    print("3. stsz (Sample Size)")
    print("=" * 70)

    trak_start, trak_end = find_video_trak(f, file_size)
    stbl_start, stbl_end = find_box_recursive(f, trak_start, trak_end, ["mdia", "minf", "stbl"])

    sample_sizes = []
    for stsz_type, stsz_start, stsz_size, stsz_data in find_boxes(f, stbl_start, stbl_end, ["stsz"]):
        f.seek(stsz_data)
        version = read_u8(f)
        f.read(3)  # flags
        default_size = read_u32(f)
        sample_count = read_u32(f)
        print(f"  Default sample size: {default_size}")
        print(f"  Sample count: {sample_count}")

        if default_size == 0:
            print(f"\n  First 15 sample sizes:")
            for i in range(min(sample_count, 15)):
                sz = read_u32(f)
                sample_sizes.append(sz)
                print(f"    Sample {i}: {sz} bytes")
            # Read remaining for stats
            for i in range(15, sample_count):
                sz = read_u32(f)
                sample_sizes.append(sz)
        else:
            sample_sizes = [default_size] * sample_count
            print(f"  All {sample_count} samples have uniform size: {default_size} bytes")
            print(f"  First 15 would all be {default_size} bytes")

        if sample_sizes:
            print(f"\n  Stats: min={min(sample_sizes)}, max={max(sample_sizes)}, "
                  f"avg={sum(sample_sizes)/len(sample_sizes):.0f}, total={sum(sample_sizes)} bytes")
        break

    return sample_sizes


def parse_stss(f, file_size):
    """Find and parse stss (sync sample) box."""
    print("\n" + "=" * 70)
    print("4. stss (Sync Sample / Random Access Points)")
    print("=" * 70)

    trak_start, trak_end = find_video_trak(f, file_size)
    stbl_start, stbl_end = find_box_recursive(f, trak_start, trak_end, ["mdia", "minf", "stbl"])

    found = False
    for stss_type, stss_start, stss_size, stss_data in find_boxes(f, stbl_start, stbl_end, ["stss"]):
        found = True
        f.seek(stss_data)
        version = read_u8(f)
        f.read(3)  # flags
        entry_count = read_u32(f)
        print(f"  Sync sample count: {entry_count}")

        print(f"  First sync samples (up to 30):")
        for i in range(min(entry_count, 30)):
            sample_number = read_u32(f)
            print(f"    Sync sample: {sample_number}")
        if entry_count > 30:
            print(f"    ... and {entry_count - 30} more")
        break

    if not found:
        print("  stss box NOT present — all samples are implicitly sync samples")


def find_stco_offsets(f, file_size):
    """Get chunk offsets from stco or co64."""
    trak_start, trak_end = find_video_trak(f, file_size)
    stbl_start, stbl_end = find_box_recursive(f, trak_start, trak_end, ["mdia", "minf", "stbl"])

    offsets = []
    # Try stco first
    for co_type, co_start, co_size, co_data in find_boxes(f, stbl_start, stbl_end, ["stco", "co64"]):
        f.seek(co_data)
        version = read_u8(f)
        f.read(3)
        entry_count = read_u32(f)
        for i in range(entry_count):
            if co_type == "co64":
                offsets.append(read_u64(f))
            else:
                offsets.append(read_u32(f))
        break

    return offsets


def find_stsc_entries(f, file_size):
    """Get sample-to-chunk mapping."""
    trak_start, trak_end = find_video_trak(f, file_size)
    stbl_start, stbl_end = find_box_recursive(f, trak_start, trak_end, ["mdia", "minf", "stbl"])

    entries = []
    for stsc_type, stsc_start, stsc_size, stsc_data in find_boxes(f, stbl_start, stbl_end, ["stsc"]):
        f.seek(stsc_data)
        version = read_u8(f)
        f.read(3)
        entry_count = read_u32(f)
        for i in range(entry_count):
            first_chunk = read_u32(f)
            samples_per_chunk = read_u32(f)
            sample_desc_idx = read_u32(f)
            entries.append((first_chunk, samples_per_chunk, sample_desc_idx))
        break
    return entries


def get_sample_offsets(f, file_size, sample_sizes):
    """Calculate absolute file offset for each sample using stco + stsc + stsz."""
    chunk_offsets = find_stco_offsets(f, file_size)
    stsc_entries = find_stsc_entries(f, file_size)

    if not chunk_offsets or not stsc_entries:
        return []

    # Build per-chunk sample counts
    num_chunks = len(chunk_offsets)
    chunk_sample_counts = [0] * num_chunks

    for i, (first_chunk, samples_per_chunk, _) in enumerate(stsc_entries):
        if i + 1 < len(stsc_entries):
            next_first = stsc_entries[i + 1][0]
        else:
            next_first = num_chunks + 1
        for c in range(first_chunk, next_first):
            if c - 1 < num_chunks:
                chunk_sample_counts[c - 1] = samples_per_chunk

    # Build sample offsets
    offsets = []
    sample_idx = 0
    for chunk_idx in range(num_chunks):
        offset = chunk_offsets[chunk_idx]
        for s in range(chunk_sample_counts[chunk_idx]):
            if sample_idx < len(sample_sizes):
                offsets.append(offset)
                offset += sample_sizes[sample_idx]
                sample_idx += 1

    return offsets


def parse_mdat_samples(f, file_size, sample_sizes, nal_length_size):
    """Read and display first 5 samples from mdat."""
    print("\n" + "=" * 70)
    print("5. First 5 mdat Samples — NAL Analysis")
    print("=" * 70)
    print(f"  NAL length field size: {nal_length_size} bytes (from avcC)")

    sample_offsets = get_sample_offsets(f, file_size, sample_sizes)

    if not sample_offsets:
        print("  ERROR: Could not determine sample offsets")
        return

    print(f"  Total samples with offsets: {len(sample_offsets)}")

    for i in range(min(5, len(sample_offsets))):
        offset = sample_offsets[i]
        size = sample_sizes[i]
        f.seek(offset)
        data = f.read(min(size, 200))  # Read enough for analysis

        print(f"\n  --- Sample {i} ---")
        print(f"  File offset: 0x{offset:X} ({offset})")
        print(f"  Sample size: {size} bytes")
        print(f"  First 20 hex bytes: {data[:20].hex()}")

        # Parse NALs within this sample (AVCC format: length-prefixed)
        pos = 0
        nal_idx = 0
        while pos + nal_length_size <= len(data) and pos < size:
            if nal_length_size == 4:
                nal_len = struct.unpack(">I", data[pos:pos+4])[0]
            elif nal_length_size == 2:
                nal_len = struct.unpack(">H", data[pos:pos+2])[0]
            elif nal_length_size == 1:
                nal_len = data[pos]
            else:
                break

            nal_start = pos + nal_length_size
            if nal_start < len(data):
                nal_header = data[nal_start]
                nal_type = nal_header & 0x1F
                nal_ref_idc = (nal_header >> 5) & 0x03
                forbidden = (nal_header >> 7) & 0x01

                nal_type_names = {
                    1: "non-IDR slice", 2: "slice part A", 3: "slice part B",
                    4: "slice part C", 5: "IDR slice", 6: "SEI", 7: "SPS",
                    8: "PPS", 9: "AUD", 10: "end of seq", 11: "end of stream",
                    12: "filler"
                }
                type_name = nal_type_names.get(nal_type, f"unknown({nal_type})")

                nal_hex = data[nal_start:nal_start + min(16, nal_len)].hex()
                print(f"    NAL[{nal_idx}]: length={nal_len}, type={nal_type} ({type_name}), "
                      f"nal_ref_idc={nal_ref_idc}, forbidden={forbidden}")
                print(f"      First bytes: {nal_hex}")

            pos = nal_start + nal_len
            nal_idx += 1
            if nal_idx >= 10:  # safety limit
                if pos < size:
                    print(f"    ... (more NALs in this sample)")
                break


def main():
    with open(FILE, "rb") as f:
        f.seek(0, 2)
        file_size = f.tell()
        f.seek(0)

        print(f"File: {FILE}")
        print(f"File size: {file_size} bytes ({file_size/1024/1024:.2f} MB)")

        # List top-level boxes
        print(f"\nTop-level boxes:")
        for box_type, box_start, box_size, data_start in find_boxes(f, 0, file_size):
            print(f"  {box_type} @ 0x{box_start:X}, size={box_size}")

        nal_length_size = parse_avcc(f, file_size)
        parse_stts(f, file_size)
        sample_sizes = parse_stsz(f, file_size)
        parse_stss(f, file_size)
        parse_mdat_samples(f, file_size, sample_sizes, nal_length_size)


if __name__ == "__main__":
    main()
