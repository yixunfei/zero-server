/*
 * ${generatedMarker}. Do not edit manually.
 */

const MAX_INT_BYTES = 5;

/**
 * 生成协议 payload DTO 的轻量标记接口。
 */
export interface ZeroGeneratedPayload {}

/**
 * zeroServer 二进制写入器。
 */
export class ZeroWriter {
  private buffer: number[] = [];

  toUint8Array(): Uint8Array {
    return new Uint8Array(this.buffer);
  }

  writeBoolean(value: boolean): void {
    this.writeByte(value ? 1 : 0);
  }

  writeByte(value: number): void {
    this.buffer.push(value & 0xff);
  }

  writeShort(value: number): void {
    this.writeInt(value);
  }

  writeInt(value: number): void {
    this.writeRawVarInt32(((value << 1) ^ (value >> 31)) >>> 0);
  }

  writeUnsignedInt(value: number): void {
    if (value < 0) {
      throw new Error('unsigned int must not be negative');
    }
    this.writeRawVarInt32(value >>> 0);
  }

  writeLong(value: bigint): void {
    this.writeRawVarInt64((value << 1n) ^ (value >> 63n));
  }

  writeUnsignedLong(value: bigint): void {
    if (value < 0n) {
      throw new Error('unsigned long must not be negative');
    }
    this.writeRawVarInt64(value);
  }

  writeFloat(value: number): void {
    const bytes = new Uint8Array(4);
    new DataView(bytes.buffer).setFloat32(0, value, false);
    this.writeRawBytes(bytes);
  }

  writeDouble(value: number): void {
    const bytes = new Uint8Array(8);
    new DataView(bytes.buffer).setFloat64(0, value, false);
    this.writeRawBytes(bytes);
  }

  writeString(value: string): void {
    this.writeByteArray(new TextEncoder().encode(value ?? ''));
  }

  writeByteArray(value: Uint8Array | null | undefined): void {
    if (!value || value.length === 0) {
      this.writeUnsignedInt(0);
      return;
    }
    this.writeUnsignedInt(value.length);
    this.writeRawBytes(value);
  }

  writeArray<T>(values: T[], itemWriter: (writer: ZeroWriter, value: T) => void): void {
    this.writeUnsignedInt(values.length);
    for (const value of values) {
      itemWriter(this, value);
    }
  }

  writeCollection<T>(values: Iterable<T> & { length?: number; size?: number }, itemWriter: (writer: ZeroWriter, value: T) => void): void {
    const count = typeof values.length === 'number' ? values.length : values.size ?? 0;
    this.writeUnsignedInt(count);
    for (const value of values) {
      itemWriter(this, value);
    }
  }

  writeMap<K, V>(
    values: Map<K, V>,
    keyWriter: (writer: ZeroWriter, key: K) => void,
    valueWriter: (writer: ZeroWriter, value: V) => void
  ): void {
    this.writeUnsignedInt(values.size);
    for (const [key, value] of values) {
      keyWriter(this, key);
      valueWriter(this, value);
    }
  }

  writePresenceBits(fieldCount: number, presentPredicate: (index: number) => boolean): void {
    if (fieldCount < 0) {
      throw new Error('fieldCount must not be negative');
    }
    this.writeUnsignedInt(fieldCount);
    const byteCount = Math.floor((fieldCount + 7) / 8);
    for (let byteIndex = 0; byteIndex < byteCount; byteIndex++) {
      let word = 0;
      const base = byteIndex * 8;
      const end = Math.min(fieldCount, base + 8);
      for (let bitIndex = base; bitIndex < end; bitIndex++) {
        if (presentPredicate(bitIndex)) {
          word |= 1 << (bitIndex - base);
        }
      }
      this.writeByte(word);
    }
  }

  beginObject(): number {
    const marker = this.buffer.length;
    for (let index = 0; index < MAX_INT_BYTES; index++) {
      this.buffer.push(0);
    }
    return marker;
  }

  endObject(marker: number): void {
    if (marker < 0 || marker + MAX_INT_BYTES > this.buffer.length) {
      throw new Error('invalid object marker');
    }
    const contentStart = marker + MAX_INT_BYTES;
    const length = this.buffer.length - contentStart;
    const encoded = encodeUnsignedInt(length);
    this.buffer.splice(marker, MAX_INT_BYTES, ...encoded);
  }

  private writeRawBytes(bytes: Uint8Array): void {
    for (const value of bytes) {
      this.buffer.push(value);
    }
  }

  private writeRawVarInt32(value: number): void {
    let remaining = value >>> 0;
    while ((remaining & ~0x7f) !== 0) {
      this.writeByte((remaining & 0x7f) | 0x80);
      remaining >>>= 7;
    }
    this.writeByte(remaining);
  }

  private writeRawVarInt64(value: bigint): void {
    let remaining = BigInt.asUintN(64, value);
    while ((remaining & ~0x7fn) !== 0n) {
      this.writeByte(Number((remaining & 0x7fn) | 0x80n));
      remaining >>= 7n;
    }
    this.writeByte(Number(remaining));
  }
}

/**
 * zeroServer 二进制读取器。
 */
export class ZeroReader {
  private index: number;
  private readonly limit: number;
  private readonly view: DataView;

  constructor(private readonly buffer: Uint8Array, offset = 0, length = buffer.length - offset) {
    this.index = offset;
    this.limit = offset + length;
    this.view = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  }

  readBoolean(): boolean {
    return this.readByte() !== 0;
  }

  readByte(): number {
    this.requireReadable(1);
    return this.buffer[this.index++];
  }

  readShort(): number {
    return this.readInt();
  }

  readInt(): number {
    const raw = this.readRawVarInt32();
    return (raw >>> 1) ^ -(raw & 1);
  }

  readUnsignedInt(): number {
    const value = this.readRawVarInt32();
    if (value > 0x7fffffff) {
      throw new Error('unsigned int exceeds positive int range');
    }
    return value;
  }

  readLong(): bigint {
    const raw = this.readRawVarInt64();
    return (raw >> 1n) ^ -(raw & 1n);
  }

  readUnsignedLong(): bigint {
    const value = this.readRawVarInt64();
    if (value < 0n) {
      throw new Error('unsigned long exceeds positive long range');
    }
    return value;
  }

  readFloat(): number {
    this.requireReadable(4);
    const value = this.view.getFloat32(this.index, false);
    this.index += 4;
    return value;
  }

  readDouble(): number {
    this.requireReadable(8);
    const value = this.view.getFloat64(this.index, false);
    this.index += 8;
    return value;
  }

  readString(): string {
    const length = this.readUnsignedInt();
    if (length === 0) {
      return '';
    }
    this.requireReadable(length);
    const bytes = this.buffer.subarray(this.index, this.index + length);
    this.index += length;
    return new TextDecoder().decode(bytes);
  }

  readByteArray(): Uint8Array {
    const length = this.readUnsignedInt();
    if (length === 0) {
      return new Uint8Array();
    }
    this.requireReadable(length);
    const value = this.buffer.slice(this.index, this.index + length);
    this.index += length;
    return value;
  }

  readArray<T>(itemReader: (reader: ZeroReader) => T): T[] {
    const count = this.readUnsignedInt();
    const values: T[] = [];
    for (let index = 0; index < count; index++) {
      values.push(itemReader(this));
    }
    return values;
  }

  readList<T>(itemReader: (reader: ZeroReader) => T): T[] {
    return this.readArray(itemReader);
  }

  readSet<T>(itemReader: (reader: ZeroReader) => T): Set<T> {
    const values = this.readArray(itemReader);
    return new Set(values);
  }

  readMap<K, V>(keyReader: (reader: ZeroReader) => K, valueReader: (reader: ZeroReader) => V): Map<K, V> {
    const count = this.readUnsignedInt();
    const values = new Map<K, V>();
    for (let index = 0; index < count; index++) {
      values.set(keyReader(this), valueReader(this));
    }
    return values;
  }

  beginObject(): number {
    const length = this.readUnsignedInt();
    const end = this.index + length;
    if (end < this.index || end > this.limit) {
      throw new Error('object length exceeds readable bytes');
    }
    return end;
  }

  hasRemainingInObject(objectEnd: number): boolean {
    if (objectEnd < this.index || objectEnd > this.limit) {
      throw new Error('invalid object end index');
    }
    return this.index < objectEnd;
  }

  endObject(objectEnd: number): void {
    if (objectEnd < this.index || objectEnd > this.limit) {
      throw new Error('invalid object end index');
    }
    this.index = objectEnd;
  }

  readPresenceBits(): boolean[] {
    const fieldCount = this.readUnsignedInt();
    const values = new Array<boolean>(fieldCount);
    const byteCount = Math.floor((fieldCount + 7) / 8);
    for (let byteIndex = 0; byteIndex < byteCount; byteIndex++) {
      const word = this.readByte();
      const base = byteIndex * 8;
      const end = Math.min(fieldCount, base + 8);
      for (let bitIndex = base; bitIndex < end; bitIndex++) {
        values[bitIndex] = ((word >>> (bitIndex - base)) & 1) !== 0;
      }
    }
    return values;
  }

  private readRawVarInt32(): number {
    let shift = 0;
    let result = 0;
    for (let index = 0; index < 5; index++) {
      const value = this.readByte();
      result |= (value & 0x7f) << shift;
      if ((value & 0x80) === 0) {
        if (index === 4 && (value & 0xf0) !== 0) {
          throw new Error('unsigned int varint overflow');
        }
        return result >>> 0;
      }
      shift += 7;
    }
    throw new Error('unsigned int varint is too long');
  }

  private readRawVarInt64(): bigint {
    let shift = 0n;
    let result = 0n;
    for (let index = 0; index < 10; index++) {
      const value = this.readByte();
      result |= BigInt(value & 0x7f) << shift;
      if ((value & 0x80) === 0) {
        if (index === 9 && (value & 0xfe) !== 0) {
          throw new Error('unsigned long varint overflow');
        }
        return result;
      }
      shift += 7n;
    }
    throw new Error('unsigned long varint is too long');
  }

  private requireReadable(length: number): void {
    if (length < 0 || this.index + length > this.limit) {
      throw new Error('not enough readable bytes');
    }
  }
}

function encodeUnsignedInt(value: number): number[] {
  const result: number[] = [];
  let remaining = value >>> 0;
  while ((remaining & ~0x7f) !== 0) {
    result.push((remaining & 0x7f) | 0x80);
    remaining >>>= 7;
  }
  result.push(remaining);
  return result;
}
