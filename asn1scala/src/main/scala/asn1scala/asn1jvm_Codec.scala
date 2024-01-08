package asn1scala

import stainless.*
import stainless.lang.{None => None, ghost => ghostExpr, Option => Option, _}
import stainless.collection.*
import stainless.annotation.*
import stainless.proof.*
import stainless.math.*
import StaticChecks.{assert => stainlessAssert, _}

val masks2: Array[UInt] = Array(
   0x00000000, //         0 / 0000 0000 0000 0000 0000 0000 0000 0000 / 0x0000 0000
   0x000000FF, //       255 / 0000 0000 0000 0000 0000 0000 1111 1111 / 0x0000 00FF
   0x0000FF00, //     65280 / 0000 0000 0000 0000 1111 1111 0000 0000 / 0x0000 FF00
   0x00FF0000, //  16711680 / 0000 0000 1111 1111 0000 0000 0000 0000 / 0x00FF 0000
   0xFF000000, // -16777216 / 1111 1111 0000 0000 0000 0000 0000 0000 / 0xFF00 0000
)

val CHAR_MINUS: ASCIIChar = 45
val CHAR_PLUS: ASCIIChar = 43
val CHAR_ZERO: ASCIIChar = 48
val CHAR_NINE: ASCIIChar = 57
val CHAR_0000: ASCIIChar = 0

/***********************************************************************************************/
/**    Byte Stream Functions                                                                  **/
/***********************************************************************************************/
def ByteStream_Init(count: Int): ByteStream = {
   ByteStream(Array.fill(count)(0), 0, false)
}

@extern
def runtimeAssert(condition: Boolean, s: String =""): Unit = assert(condition, s)

@extern
def writeToStdErr(s: String): Unit = Console.err.println(s)

@extern
def ByteStream_AttachBuffer(pStrm: ByteStream, buf: Array[UByte]): Unit = {
   pStrm.buf = buf // Illegal aliasing, therefore we need to workaround with this @extern...
   pStrm.currentByte = 0
}.ensuring(_ => pStrm.buf == buf && pStrm.currentByte == 0 && pStrm.EncodeWhiteSpace == old(pStrm).EncodeWhiteSpace)

def ByteStream_GetLength(pStrm: ByteStream): Int = {
   pStrm.currentByte
}

/***********************************************************************************************/
/**    Bit Stream Functions                                                                   **/
/***********************************************************************************************/
def BitString_equal(arr1: Array[UByte], arr2: Array[UByte]): Boolean = {
   arraySameElements(arr1, arr2)
}

// TODO remove
//def BitStream_Init(count: Int): BitStream = {
//   BitStream(Array.fill(count)(0))
//}

/**
 * Parent class for the PER Codec that is used by ACN and UPER
 *
 * @param count represents the number of bytes in the internal buffer
 */
trait Codec {

   def bitStream: BitStream

   /** ******************************************************************************************** */
   /** ******************************************************************************************** */
   /** ******************************************************************************************** */
   /** ******************************************************************************************** */
   /** Integer Functions                                                                     * */
   /** ******************************************************************************************** */
   /** ******************************************************************************************** */
   /** ******************************************************************************************** */

   /** ******************************************************************************************** */
   def encodeNonNegativeInteger32Neg(v: Int, negate: Boolean): Unit = {
      var cc: UInt = 0
      var curMask: UInt = 0
      var pbits: UInt = 0

      if v == 0 then
         return ()

      if v >>> 8 == 0 then
         cc = 8
         curMask = 0x80
      else if v >>> 16 == 0 then
         cc = 16
         curMask = 0x8000
      else if v >>> 24 == 0 then
         cc = 24
         curMask = 0x800000
      else
         cc = 32
         curMask = 0x80000000

      while (v & curMask) == 0 do
         decreases(cc)
         curMask >>>= 1
         cc -= 1

      pbits = cc % 8
      if pbits > 0 then
         cc -= pbits
         var b = (v >>> cc).toByte
         appendPartialByte(if negate then (~b).toByte else b, pbits.toByte)

      while cc > 0 do
         decreases(cc)
         val t1: UInt = v.toInt & masks2(cc >>> 3)
         cc -= 8

         var b = t1 >>> cc
         if negate then
            b = ~b

         appendByte(b.toUnsignedByte)
   }

   def decodeNonNegativeInteger32Neg(nBitsVal : Int): Option[UInt] = {

      var v: UInt = 0

      var nBits = nBitsVal
      while nBits >= 8 do
         decreases(nBits)
         v = v << 8

         readByte() match
            case None() => return None()
            case Some(ub) =>
               // mask the Byte-Bits, because negative values eg. -1 (1111 1111)
               // will be casted to an Int -1 (1111 ... 1111)
               v = v | (ub & 0xFF)

         nBits -= 8

      if nBits != 0 then
         v = v << nBits
         readPartialByte(nBits.toByte) match
            case None() => return None()
            case Some(ub) => v = v | (ub & 0xFF)

      Some(v)
   }

   def encodeNonNegativeInteger(v: ULong): Unit = {
      if v >>> 32 == 0 then
         encodeNonNegativeInteger32Neg(v.toInt, false)
      else
         val hi = (v >>> 32).toInt
         val lo = v.toInt
         encodeNonNegativeInteger32Neg(hi, false)

         val nBits: Int = GetNumberOfBitsForNonNegativeInteger(lo.toLong << 32 >>> 32) // TODO: is this easier?
         appendNBitZero(32 - nBits)
         encodeNonNegativeInteger32Neg(lo, false)
   }

   def decodeNonNegativeInteger(nBits: Int): Option[ULong] = {
      if nBits <= 32 then
         decodeNonNegativeInteger32Neg(nBits) match
            case None() => return None()
            case Some(lo) =>
               return Some(lo & 0xFFFFFFFFL)

      val hi_ret = decodeNonNegativeInteger32Neg(32)
      val lo_ret = decodeNonNegativeInteger32Neg(nBits - 32)

      (hi_ret, lo_ret) match
         case (Some(hi), Some(lo)) =>
            var v: ULong = hi & 0xFFFFFFFFL
            v = v << nBits - 32L
            v |= lo & 0xFFFFFFFFL
            return Some(v)
         case _ => return None()
      //else
      //    return decodeNonNegativeInteger32Neg(v, nBits)
   }

   def encodeNonNegativeIntegerNeg(v: ULong): Unit = {
      if v >>> 32 == 0 then
         encodeNonNegativeInteger32Neg(v.toInt, true)
      else
         // TODO: Check Int/Long
         val hi = (v >>> 32).toInt
         var lo = v.toInt
         encodeNonNegativeInteger32Neg(hi, true)

         /*bug !!!!*/
         if true then // TODO, the negate flag was always true
            lo = ~lo
         val nBits = GetNumberOfBitsForNonNegativeInteger(lo.toLong)
         appendNBitZero(32 - nBits)
         encodeNonNegativeInteger32Neg(lo, false)
   }

   def encodeConstrainedWholeNumber(v: Long, min: Long, max: Long): Unit = {
      require(min <= max)
      require(
         min >= 0 && max >= 0 ||
            min < 0 && max < 0 ||
            min <= (Long.MaxValue >> 1) && max <= min + (Long.MaxValue >> 1)
      )
      require(min <= v && v <= max)

      val range = max - min
      stainlessAssert(range >= 0)
      if range == 0 then
         return;

      // runtime only right now
      if range < 0 then
         writeToStdErr("Range is bigger than the biggest primitive on the JVM - unsafe!")

      // get number of bits that get written
      val nRangeBits: Int = GetNumberOfBitsForNonNegativeInteger(range)

      // get value that gets written
      val encVal = v - min
      stainlessAssert(encVal >= 0)

      @ghost val nEncValBits = GetNumberOfBitsForNonNegativeInteger(encVal)
      stainlessAssert(nRangeBits >= nEncValBits)

      val done = appendBitsNBitFirstToLSB(encVal, nRangeBits)
      if !done then
         writeToStdErr("precondition for appendBitsLSBFirst not met")
   }

   // TODO remove - does exactly the same as encodeConstrainedWholeNumber
//   def encodeConstraintPosWholeNumber(v: ULong, min: ULong, max: ULong): Unit = {
//      require(max >= 0 && max <= Long.MaxValue)
//      require(min >= 0 && min <= max)
//      require(min <= v && v <= max)
//
//      val range: ULong = (max - min)
//      if range == 0 then
//         return
//
//      val nRangeBits: Int = GetNumberOfBitsForNonNegativeInteger(range)
//      val nBits: Int = GetNumberOfBitsForNonNegativeInteger(v - min)
//      appendNBitZero(nRangeBits - nBits)
//      encodeNonNegativeInteger(v - min)
//   }

   def decodeConstraintWholeNumber(min: Long, max: Long): Option[Long] = {
      require(min <= max)
      require(
         min >= 0 && max >= 0 ||
         min < 0 && max < 0 ||
         min <= (Long.MaxValue >> 1) && max <= min + (Long.MaxValue >> 1)
      )

      val range: ULong = max - min
      if range == 0 then
         return Some(min)

      val nRangeBits = GetNumberOfBitsForNonNegativeInteger(range)

      decodeNonNegativeInteger(nRangeBits) match
         case None() => return None()
         case Some(ul) => return Some(ul + min)
   }

   def decodeConstraintWholeNumberByte(min: Byte, max: Byte): Option[Byte] = {

      decodeConstraintWholeNumber(min.toLong, max.toLong) match
         case None() => None()
         case Some(l) => Some(l.toByte)
   }

   def decodeConstraintWholeNumberShort(min: Short, max: Short): Option[Short] = {

      decodeConstraintWholeNumber(min, max) match
         case None() => None()
         case Some(l) => Some(l.toShort)
   }

   def decodeConstraintWholeNumberInt(min: Int, max: Int): Option[Int] = {

      decodeConstraintWholeNumber(min, max) match
         case None() => None()
         case Some(l) => Some(l.toInt)
   }

   def decodeConstraintWholeNumberUByte(min: UByte, max: UByte): Option[UByte] = {

      decodeConstraintWholeNumber(min.unsignedToLong, max.unsignedToLong) match
         case None() => None()
         case Some(l) => Some(l.toByte)
   }

   def decodeConstraintWholeNumberUShort(min: UShort, max: UShort): Option[UShort] = {

      decodeConstraintWholeNumber(min.unsignedToLong, max.unsignedToLong) match
         case None() => None()
         case Some(l) => Some(l.toShort)
   }

   def decodeConstraintWholeNumberUInt(min: UInt, max: UInt): Option[UInt] = {

      decodeConstraintWholeNumber(min.unsignedToLong, max.unsignedToLong) match
         case None() => None()
         case Some(l) => Some(l.toInt)
   }

   def decodeConstraintPosWholeNumber(min: ULong, max: ULong): Option[ULong] = {
      require(max >= 0 && max <= Long.MaxValue)
      require(min >= 0 && min <= max)

      val range: ULong = max - min

      if range == 0 then
         return Some(min)

      val nRangeBits: Int = GetNumberOfBitsForNonNegativeInteger(range)

      decodeNonNegativeInteger(nRangeBits) match
         case None() => None()
         case Some(uv) => Some(uv + min)
   }

   def encodeSemiConstraintWholeNumber(v: Long, min: Long): Unit = {
      assert(v >= min)
      val nBytes: Int = GetLengthForEncodingUnsigned((v - min))

      /* encode length */
      encodeConstrainedWholeNumber(nBytes.toLong, 0, 255)
      /*8 bits, first bit is always 0*/
      /* put required zeros*/
      appendNBitZero(nBytes * 8 - GetNumberOfBitsForNonNegativeInteger((v - min)))
      /*Encode number */
      encodeNonNegativeInteger((v - min))
   }

   def encodeSemiConstraintPosWholeNumber(v: ULong, min: ULong): Unit = {
      assert(v >= min)
      val nBytes: Int = GetLengthForEncodingUnsigned(v - min)

      /* encode length */
      encodeConstrainedWholeNumber(nBytes.toLong, 0, 255)
      /*8 bits, first bit is always 0*/
      /* put required zeros*/
      appendNBitZero(nBytes * 8 - GetNumberOfBitsForNonNegativeInteger(v - min))
      /*Encode number */
      encodeNonNegativeInteger(v - min)
   }

   def decodeSemiConstraintWholeNumber(min: Long): Option[Long] = {

      var nBytes: Long = 0
      var v: Long = 0

      decodeConstraintWholeNumber(0, 255) match
         case None() => return None()
         case Some(l) => nBytes = l

      var i: Long = 0
      while i < nBytes do
         decreases(nBytes - i)

         readByte() match
            case None() => return None()
            case Some(ub) => v = (v << 8) | (ub & 0xFF).toLong

         i += 1

      v += min

      return Some(v)
   }

   def decodeSemiConstraintPosWholeNumber(min: ULong): Option[ULong] = {

      var nBytes: Long = 0
      var v: ULong = 0
      decodeConstraintWholeNumber(0, 255) match
         case None() => return None()
         case Some(l) => nBytes = l

      var i: Long = 0
      while i < nBytes do
         decreases(nBytes - i)

         readByte() match
            case None() => return None()
            case Some(ub) => v = (v << 8) | (ub & 0xFF).toLong

         i += 1
      v += min
      return Some(v)
   }

   /**
    * 8.3 Encoding of an integer value
    *
    * The encoding of an integer value shall be primitive.
    * The contents octets shall consist of one or more octets.
    *
    * @param v The value that is always encoded in the smallest possible number of octets.
    */
   def encodeUnconstrainedWholeNumber(v: Long): Unit = {
      require(bitStream.validate_offset_bytes(1 + GetLengthForEncodingSigned(v)))

      // call func that fulfills 8.3.2
      val nBytes: Int = GetLengthForEncodingSigned(v)

      // encode length - single octet
      appendByte(nBytes.toByte)

      var i = nBytes;
      (while i > 0 do
         decreases(i)

         appendByte((v >>> ((i - 1) * NO_OF_BITS_IN_BYTE)).toUnsignedByte)

         i -= 1
      ).invariant(i >= 0 && i <= nBytes)
   }

   /**
    * 8.3 Encoding of an integer value reverse OP
    *
    * To call this func at least 2 octets have to be available on the bitstream
    * The length n is the first octet, n octets with the value follow
    * Values with n > 8 are not supported
    *
    * @return decoded number
    */
   def decodeUnconstrainedWholeNumber(): Option[Long] = {
      require(bitStream.validate_offset_bytes(2))

      val nBytes = readByte() match
         case None() => return None()
         case Some(b) => b

      val valIsNegative = peekBit() match
         case Some(b) => b
         case None() => return None()

      var v: Long = if valIsNegative then -1 else 0

      var i = 0
      (while i < nBytes do
         decreases(nBytes - i)

         readByte() match
            case None() => return None()
            case Some(ub) => v = (v << 8) | (ub & 0xFF).toLong

         i += 1
      ).invariant(i >= 0 && i<= nBytes)

      Some(v)
   }

   /**
    * Facade function for real encoding
    * @param vVal real input in IEEE754 double format
    */
   @extern
   def encodeReal(vVal: Double): Unit = {
      encodeRealBitString(java.lang.Double.doubleToRawLongBits(vVal))
   }

   /**
    * Binary encoding will be used
    * REAL = M*B^E
    * where
    * M = S*N*2^F
    *
    * ENCODING is done within three parts
    * part 1 is 1 byte header
    * part 2 is 1 or more byte for exponent
    * part 3 is 3 or more byte for mantissa (N)
    *
    * First byte
    * S :0-->+, S:1-->-1
    * Base will be always be 2 (implied by 6th and 5th bit which are zero)
    * ab: F    (0..3)
    * cd:00 --> 1 byte for exponent as 2's complement
    * cd:01 --> 2 byte for exponent as 2's complement
    * cd:10 --> 3 byte for exponent as 2's complement
    * cd:11 --> 1 byte for encoding the length of the exponent, then the exponent
    *
    * 8 7 6 5 4 3 2 1
    * +-+-+-+-+-+-+-+-+
    * |1|S|0|0|a|b|c|d|
    * +-+-+-+-+-+-+-+-+
    *
    */
   private def encodeRealBitString(vVal: Long): Unit = {
      // according to T-REC-X.690 2021

      var v = vVal

      // 8.5.2 Plus Zero
      if v == DoublePosZeroBitString then
         encodeConstrainedWholeNumber(0, 0, 0xFF)
         return;

      // 8.5.3 Minus Zero
      if v == DoubleNegZeroBitString then
         encodeConstrainedWholeNumber(1, 0, 0xFF)
         encodeConstrainedWholeNumber(0x43, 0, 0xFF)
         return;

      // 8.5.9 SpecialRealValues
      if (v & ExpoBitMask) == ExpoBitMask then

      // 8.5.9 PLUS-INFINITY
         if v == DoublePosInfBitString then
            encodeConstrainedWholeNumber(1, 0, 0xFF)
            encodeConstrainedWholeNumber(0x40, 0, 0xFF)
            return;

         // 8.5.9 MINUS-INFINITY
         else if v == DoubleNegInfBitString then
            encodeConstrainedWholeNumber(1, 0, 0xFF)
            encodeConstrainedWholeNumber(0x41, 0, 0xFF)
            return;

         // 8.5.9 NOT-A-NUMBER
         else
            encodeConstrainedWholeNumber(1, 0, 0xFF)
            encodeConstrainedWholeNumber(0x42, 0, 0xFF)
            return;

      // 8.5.6 a)
      // fixed encoding style to binary
      // 8.5.7.2 exp has always base 2 - bit 0x20 and 0x10 are always 0
      // 8.5.7.3 F value is always zero - bit 0x08 and 0x04 are always 0
      var header = 0x80

      // 8.5.7.1
      if ((v & SignBitMask) == SignBitMask) { // check sign bit
         header |= 0x40
         v &= InverseSignBitMask // clear sign bit
      }

      val (exponent, mantissa) = CalculateMantissaAndExponent(v)

      val nManLen: Int = GetLengthForEncodingUnsigned(mantissa)
      runtimeAssert(nManLen <= 7) // 52 bit

      val compactExp = RemoveLeadingFFBytesIfNegative(exponent)
      val nExpLen: Int = GetLengthForEncodingUnsigned(compactExp)
      runtimeAssert(nExpLen >= 1 && nExpLen <= 2)

      // 8.5.7.4
      if nExpLen == 2 then
         header |= 0x01
      else if nExpLen == 3 then // this will never happen with this implementation
         header |= 0x02

      /* encode length */
      encodeConstrainedWholeNumber(1 + nExpLen + nManLen, 0, 0xFF)

      /* encode header */
      encodeConstrainedWholeNumber(header & 0xFF, 0, 0xFF)

      /* encode exponent */
      if exponent >= 0 then
         // fill with zeros to have a whole byte
         appendNBitZero(nExpLen * 8 - GetNumberOfBitsForNonNegativeInteger(exponent))
         encodeNonNegativeInteger(exponent)
      else
         encodeNonNegativeInteger(compactExp)

      /* encode mantissa */
      appendNBitZero(nManLen * 8 - GetNumberOfBitsForNonNegativeInteger(mantissa))
      encodeNonNegativeInteger(mantissa)
   }

   /**
    * facade function for real decoding
    * @return decoded real value in IE754 double format
    */
   @extern
   def decodeReal(): Option[Double] = {
      decodeRealBitString() match
         case None() =>
            None()
         case Some(ll) =>
            Some(java.lang.Double.longBitsToDouble(ll))
   }


   /**
    * Real decoding implementation according to the PER standard
    * @return decoded double bits as 64 bit integer
    */
   private def decodeRealBitString(): Option[Long] = {
      readByte() match
         case None() => None()
         case Some(length) =>
            // 8.5.2 Plus Zero
            if length == 0 then
               return Some(0)

            // invalid state
            if length < 0 || length > DoubleMaxLengthOfSentBytes then
               return None()

            readByte() match
               case None() => None()
               case Some(header) =>
                  // 8.5.6 a)
                  if (header.unsignedToInt & 0x80) != 0x80 then
                     return None()

                  // 8.5.9 PLUS-INFINITY
                  if header == 0x40 then
                     Some(DoublePosInfBitString)

                  // 8.5.9 MINUS-INFINITY
                  else if header == 0x41 then
                     Some(DoubleNegInfBitString)

                  // 8.5.9 NOT-A-NUMBER
                  else if header == 0x42 then
                     Some(DoubleNotANumber)

                  // 8.5.3 Minus Zero
                  else if header == 0x43 then
                     Some(DoubleNegZeroBitString)

                  // Decode 8.5.7
                  else
                     decodeRealFromBitStream(length.toInt - 1, header)
   }

   /**
    * Decode real number from bitstream, special cases are decoded by caller
    * The exponent length and other details given in the header have be be
    * decoded before calling this function
    *
    * @param lengthVal already decoded exponent length
    * @param header already decoded header
    * @return decoded real number as 64bit integer
    */
   private def decodeRealFromBitStream(lengthVal: Int, header: UByte): Option[Long] = {
      require(lengthVal >= 1 && lengthVal < DoubleMaxLengthOfSentBytes) // without header byte
      require((header.unsignedToInt & 0x80) == 0x80)
      require(bitStream.validate_offset_bytes(lengthVal))

      // 8.5.7.2 Base
      val expFactor: Int = header.unsignedToInt match
         case x if (x & 0x10) > 0 => 3 // 2^3 = 8
         case x if (x & 0x20) > 0 => 4 // 2^4 = 16
         case _ => 1 // 2^1 = 2

      // 8.5.7.3 Factor F
      val factor = 1 << ((header & 0x0C) >>> 2)

      // 8.5.7.4 Length of Exponent
      val expLen = (header & 0x03) + 1

      // sanity check
      if expLen > lengthVal then
         return None()

      // decode exponent
      var expIsNegative = false
      peekBit() match
         case Some(b) => expIsNegative = b
         case None() => runtimeAssert(false)

      var exponent: Int = if expIsNegative then 0xFF_FF_FF_FF else 0

      var i: Int = 0
      (while i < expLen do
         decreases(expLen - i)

         readByte() match
            case None() => return None()
            case Some(ub) => exponent = exponent << 8 | (ub.toInt & 0xFF)

         i += 1
         ).invariant(i >= 0 && i <= expLen)

      // decode mantissa
      val length = lengthVal - expLen
      var N: ULong = 0
      var j: Int = 0
      (while j < length do
         decreases(length - j)

         readByte() match
            case None() => return None()
            case Some(ub) => N = (N << 8) | (ub.toInt & 0xFF)

         j += 1
         ).invariant(j >= 0 && j <= length)

      var v: Long = GetDoubleBitStringByMantissaAndExp(N * factor, expFactor * exponent)

      // 8.5.7.1 Set Sign bit
      if (header & 0x40) > 0 then
         v |= SignBitMask

      Some(v)
   }

   def encodeOctetString_no_length(arr: Array[UByte], nCount: Int): Boolean = {
      appendByteArray(arr, nCount)
   }

   def decodeOctetString_no_length(nCount: Int): OptionMut[Array[UByte]] = {
      readByteArray(nCount) match
         case NoneMut() => NoneMut()
         case SomeMut(a) =>
            val arr: Array[UByte] = Array.fill(nCount + 1)(0) // TODO: why is +1 needed?
            arrayCopyOffsetLen(a, arr, 0, 0, a.length)
            SomeMut(arr)
   }

   def encodeOctetString_fragmentation(arr: Array[UByte], nCount: Int): Boolean = {
      var nRemainingItemsVar1: Int = nCount
      var nCurBlockSize1: Int = 0
      var nCurOffset1: Int = 0
      var ret: Boolean = nCount >= 0

      while nRemainingItemsVar1 >= 0x4000 && ret do
         decreases(nRemainingItemsVar1)
         if nRemainingItemsVar1 >= 0x10000 then
            nCurBlockSize1 = 0x10000
            encodeConstrainedWholeNumber(0xC4, 0, 0xFF)
         else if nRemainingItemsVar1 >= 0xC000 then
            nCurBlockSize1 = 0xC000
            encodeConstrainedWholeNumber(0xC3, 0, 0xFF)
         else if nRemainingItemsVar1 >= 0x8000 then
            nCurBlockSize1 = 0x8000
            encodeConstrainedWholeNumber(0xC2, 0, 0xFF)
         else
            nCurBlockSize1 = 0x4000
            encodeConstrainedWholeNumber(0xC1, 0, 0xFF)

         var i1: Int = nCurOffset1
         while i1 < nCurBlockSize1 + nCurOffset1 do
            decreases(nCurBlockSize1 + nCurOffset1 - i1)
            appendByte(arr(i1))
            i1 += 1

         nCurOffset1 += nCurBlockSize1
         nRemainingItemsVar1 -= nCurBlockSize1

      if ret then
         if nRemainingItemsVar1 <= 0x7F then
            encodeConstrainedWholeNumber(nRemainingItemsVar1.toLong, 0, 0xFF)
         else
            appendBit(true)
            encodeConstrainedWholeNumber(nRemainingItemsVar1.toLong, 0, 0x7FFF)


         var i1: Int = nCurOffset1
         while i1 < (nCurOffset1 + nRemainingItemsVar1) do
            decreases(nCurOffset1 + nRemainingItemsVar1 - i1)
            appendByte(arr(i1))
            i1 += 1

      return ret
   }

   def decodeOctetString_fragmentation(asn1SizeMax: Long): OptionMut[Array[UByte]] = {
      require(asn1SizeMax >= 0 && asn1SizeMax < Int.MaxValue)

      val arr: Array[UByte] = Array.fill(asn1SizeMax.toInt)(0)
      var nCount: Int = 0

      var nLengthTmp1: Long = 0
      var nRemainingItemsVar1: Long = 0
      var nCurBlockSize1: Long = 0
      var nCurOffset1: Long = 0

      // get header data
      decodeConstraintWholeNumber(0, 0xFF) match
         case None() => return NoneMut()
         case Some(l) => nRemainingItemsVar1 = l

      // 11xx_xxxx header, there is a next fragment
      while (nRemainingItemsVar1 & 0xC0) == 0xC0 do
         decreases(asn1SizeMax - nCurOffset1) // TODO: check experimental decrease

         // get current block size
         if nRemainingItemsVar1 == 0xC4 then
            nCurBlockSize1 = 0x10000
         else if nRemainingItemsVar1 == 0xC3 then
            nCurBlockSize1 = 0xC000
         else if nRemainingItemsVar1 == 0xC2 then
            nCurBlockSize1 = 0x8000
         else if nRemainingItemsVar1 == 0xC1 then
            nCurBlockSize1 = 0x4000
         else
            return NoneMut()

         // fill current payload fragment into dest
         var i1: Int = nCurOffset1.toInt
         while (nCurOffset1 + nCurBlockSize1 <= asn1SizeMax) && (i1 < (nCurOffset1 + nCurBlockSize1).toInt) do
            decreases((nCurOffset1 + nCurBlockSize1).toInt - i1)
            readByte() match
               case None() => return NoneMut()
               case Some(ub) => arr(i1) = ub
            i1 += 1

         // sum combined length
         nLengthTmp1 += nCurBlockSize1
         // set offset for next run
         nCurOffset1 += nCurBlockSize1

         // get next header
         decodeConstraintWholeNumber(0, 0xFF) match
            case None() => return NoneMut()
            case Some(l) => nRemainingItemsVar1 = l

      // 1000_0000 header, last fragment has size bigger than 255 - current byte is upper, need to get lower
      if (nRemainingItemsVar1 & 0x80) > 0 then

         nRemainingItemsVar1 <<= 8 // put upper at correct position
         // get size (lower byte)
         decodeConstraintWholeNumber(0, 0xFF) match
            case None() => return NoneMut()
            case Some(l) =>
               nRemainingItemsVar1 |= l // combine 15bit (7 upper, 8 lower) into size
               nRemainingItemsVar1 &= 0x7FFF // clear the control bit

      if (nCurOffset1 + nRemainingItemsVar1 <= asn1SizeMax) then
         var i1: Int = nCurOffset1.toInt

         // fill last payload fragment into dest
         while i1 < (nCurOffset1 + nRemainingItemsVar1).toInt do
            decreases((nCurOffset1 + nRemainingItemsVar1).toInt - i1)
            readByte() match
               case None() => return NoneMut()
               case Some(ub) => arr(i1) = ub
            i1 += 1

         // add remainingSize to already written size - this var holds the absolut number in all fragments
         nLengthTmp1 += nRemainingItemsVar1

         // resize output array and copy data
         if (nLengthTmp1 >= 1) && (nLengthTmp1 <= asn1SizeMax) then
            val newArr: Array[UByte] = Array.fill(nLengthTmp1.toInt)(0)
            arrayCopyOffsetLen(arr, newArr, 0, 0, newArr.length)
            return SomeMut(newArr)
         else
            return NoneMut()

      NoneMut()
   }

   def encodeOctetString(arr: Array[UByte], nCount: Int, asn1SizeMin: Long, asn1SizeMax: Long): Boolean = {
      var ret: Boolean = nCount.toLong >= asn1SizeMin && nCount.toLong <= asn1SizeMax

      if ret then
         if asn1SizeMax < 65536 then
            if asn1SizeMin != asn1SizeMax then
               encodeConstrainedWholeNumber(nCount.toLong, asn1SizeMin, asn1SizeMax)
            ret = encodeOctetString_no_length(arr, nCount)

         else
            ret = encodeOctetString_fragmentation(arr, nCount)

      return ret
   }

   def decodeOctetString(asn1SizeMin: Long, asn1SizeMax: Long): OptionMut[Array[UByte]] = {

      if asn1SizeMax < 65536 then
         var nCount: Int = 0
         if asn1SizeMin != asn1SizeMax then
            decodeConstraintWholeNumber(asn1SizeMin, asn1SizeMax) match
               case None() => return NoneMut()
               case Some(l) => nCount = l.toInt
         else
            nCount = asn1SizeMin.toInt

         if (nCount >= asn1SizeMin && nCount <= asn1SizeMax) then
            return decodeOctetString_no_length(nCount)
         else
            return NoneMut()

      else
         return decodeOctetString_fragmentation(asn1SizeMax)

   }

   def encodeBitString(arr: Array[UByte], nCount: Int, asn1SizeMin: Long, asn1SizeMax: Long): Boolean = {
      if asn1SizeMax < 65536 then
         if asn1SizeMin != asn1SizeMax then
            encodeConstrainedWholeNumber(nCount.toLong, asn1SizeMin, asn1SizeMax)

         appendBitsMSBFirst(arr, nCount)

      else
         var nRemainingItemsVar1: Long = nCount.toLong
         var nCurBlockSize1: Long = 0
         var nCurOffset1: Long = 0
         while nRemainingItemsVar1 >= 0x4000 do
            decreases(nRemainingItemsVar1)

            if nRemainingItemsVar1 >= 0x10000 then
               nCurBlockSize1 = 0x10000
               encodeConstrainedWholeNumber(0xC4, 0, 0xFF)

            else if nRemainingItemsVar1 >= 0xC000 then
               nCurBlockSize1 = 0xC000
               encodeConstrainedWholeNumber(0xC3, 0, 0xFF)
            else if nRemainingItemsVar1 >= 0x8000 then
               nCurBlockSize1 = 0x8000
               encodeConstrainedWholeNumber(0xC2, 0, 0xFF)
            else
               nCurBlockSize1 = 0x4000
               encodeConstrainedWholeNumber(0xC1, 0, 0xFF)

            val t: Array[UByte] = Array.fill(nCurBlockSize1.toInt)(0) // STAINLESS: arr.slice((nCurOffset1 / 8).toInt, (nCurOffset1 / 8).toInt + nCurBlockSize1.toInt)
            appendBitsMSBFirst(t, nCurBlockSize1.toInt)
            nCurOffset1 += nCurBlockSize1
            nRemainingItemsVar1 -= nCurBlockSize1


         if nRemainingItemsVar1 <= 0x7F then
            encodeConstrainedWholeNumber(nRemainingItemsVar1, 0, 0xFF)
         else
            appendBit(true)
            encodeConstrainedWholeNumber(nRemainingItemsVar1, 0, 0x7FFF)

         val t: Array[UByte] = Array.fill(nRemainingItemsVar1.toInt)(0) // STAINLESS: arr.slice((nCurOffset1 / 8).toInt, (nCurOffset1 / 8).toInt + nRemainingItemsVar1.toInt)
         appendBitsMSBFirst(t, nRemainingItemsVar1.toInt)

      true
   }

   def decodeBitString(asn1SizeMin: Long, asn1SizeMax: Long): OptionMut[Array[UByte]] = {
      require(asn1SizeMax <= Int.MaxValue)

      if (asn1SizeMax < 65536) {
         var nCount: Long = 0
         if asn1SizeMin != asn1SizeMax then
            decodeConstraintWholeNumber(asn1SizeMin, asn1SizeMax) match
               case None() => return NoneMut()
               case Some(l) => nCount = l
         else
            nCount = asn1SizeMin

         return readBits(nCount.toInt)

      } else {
         var nRemainingItemsVar1: Long = 0
         var nCurBlockSize1: Long = 0
         var nCurOffset1: Long = 0
         var nLengthTmp1: Long = 0
         decodeConstraintWholeNumber(0, 0xFF) match
            case None() => return NoneMut()
            case Some(l) => nRemainingItemsVar1 = l

         val arr: Array[UByte] = Array.fill(asn1SizeMax.toInt)(0)
         while (nRemainingItemsVar1 & 0xC0) == 0xC0 do
            decreases(asn1SizeMax - nCurOffset1) // TODO: check experimental decrease
            if nRemainingItemsVar1 == 0xC4 then
               nCurBlockSize1 = 0x10000
            else if nRemainingItemsVar1 == 0xC3 then
               nCurBlockSize1 = 0xC000
            else if nRemainingItemsVar1 == 0xC2 then
               nCurBlockSize1 = 0x8000
            else if nRemainingItemsVar1 == 0xC1 then
               nCurBlockSize1 = 0x4000
            else
               return NoneMut()

            /*COVERAGE_IGNORE*/
            if nCurOffset1 + nCurBlockSize1 > asn1SizeMax then
               return NoneMut()
            /*COVERAGE_IGNORE*/

            readBits(nCurBlockSize1.toInt) match
               case NoneMut() => return NoneMut()
               case SomeMut(t) =>
                  arrayCopyOffsetLen(t, arr, 0, (nCurOffset1 / 8).toInt, nCurBlockSize1.toInt)
                  nLengthTmp1 += nCurBlockSize1
                  nCurOffset1 += nCurBlockSize1
                  decodeConstraintWholeNumber(0, 0xFF) match
                     case None() => return NoneMut()
                     case Some(l) => nRemainingItemsVar1 = l

         if (nRemainingItemsVar1 & 0x80) > 0 then
            nRemainingItemsVar1 <<= 8
            decodeConstraintWholeNumber(0, 0xFF) match
               case None() => return NoneMut()
               case Some(l) =>
                  nRemainingItemsVar1 |= l
                  nRemainingItemsVar1 &= 0x7FFF

         if (nCurOffset1 + nRemainingItemsVar1 <= asn1SizeMax) then

            readBits(nRemainingItemsVar1.toInt) match
               case NoneMut() => return NoneMut()
               case SomeMut(t) =>
                  arrayCopyOffsetLen(t, arr, 0, (nCurOffset1 / 8).toInt, nRemainingItemsVar1.toInt)
                  nLengthTmp1 += nRemainingItemsVar1
                  if (nLengthTmp1 >= 1) && (nLengthTmp1 <= asn1SizeMax) then
                     return SomeMut(arr)
      }
      return NoneMut()
   }

   def appendBitOne(): Boolean = {
      require(bitStream.validate_offset_bit())

      val isValidPrecondition = bitStream.validate_offset_bit()
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      if isValidPrecondition then
         bitStream.appendBitOne()

      isValidPrecondition
   }

   def appendBitZero(): Boolean = {
      require(bitStream.validate_offset_bit())

      val isValidPrecondition = bitStream.validate_offset_bit()
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      if isValidPrecondition then
         bitStream.appendBitZero()

      isValidPrecondition
   }

   def appendNBitZero(nBits: Long): Boolean = {
      require(bitStream.validate_offset_bits(nBits))

      val isValidPrecondition = bitStream.validate_offset_bits(nBits)
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      if isValidPrecondition then
         bitStream.appendNBitZero(nBits)

      isValidPrecondition
   }

   def appendNBitOne(nBits: Long): Boolean = {
      require(bitStream.validate_offset_bits(nBits))

      val isValidPrecondition = bitStream.validate_offset_bits(nBits)
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      if isValidPrecondition then
         bitStream.appendNBitOne(nBits)

      isValidPrecondition
   }

   def appendBitsLSBFirst(v: Long, nBits: Int): Boolean = { // TODO remove if never used
      require(bitStream.validate_offset_bits(nBits))

      val isValidPrecondition = bitStream.validate_offset_bits(nBits)
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      if isValidPrecondition then
         bitStream.appendBitsLSBFirst(v, nBits)

      isValidPrecondition
   }

   def appendBitsNBitFirstToLSB(v: Long, nBits: Int): Boolean = {
      require(bitStream.validate_offset_bits(nBits))

      val isValidPrecondition = bitStream.validate_offset_bits(nBits)
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      if isValidPrecondition then
         bitStream.appendBitsNBitFirstToLSB(v, nBits)

      isValidPrecondition
   }

   def appendBitsMSBFirst(srcBuffer: Array[UByte], nBits: Long): Boolean = {
      require(bitStream.validate_offset_bits(nBits))

      val isValidPrecondition = bitStream.validate_offset_bits(nBits)
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      if isValidPrecondition then
         bitStream.appendBitsMSBFirst(srcBuffer, nBits)

      isValidPrecondition
   }

   def appendBit(v: Boolean): Boolean = {
      require(bitStream.validate_offset_bit())

      val isValidPrecondition = bitStream.validate_offset_bit()
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      if isValidPrecondition then
         bitStream.appendBit(v)

      isValidPrecondition
   }

   def readBit(): Option[Boolean] = {
      require(bitStream.validate_offset_bit())

      val isValidPrecondition = bitStream.validate_offset_bit()
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      isValidPrecondition match
         case true => Some(bitStream.readBit())
         case false => None()
   }

   def peekBit(): Option[Boolean] = {
      require(bitStream.validate_offset_bit())

      val isValidPrecondition = bitStream.validate_offset_bits(1)
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      isValidPrecondition match
         case true => Some(bitStream.peekBit())
         case false => None()
   }

   def appendByte(value: Byte): Boolean = {
      require(bitStream.validate_offset_byte())

      val isValidPrecondition = bitStream.validate_offset_byte()
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      if isValidPrecondition then
         bitStream.appendByte(value)

      isValidPrecondition
   }

   def readByte(): Option[UByte] = {
      require(bitStream.validate_offset_byte())

      val isValidPrecondition = bitStream.validate_offset_byte()
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      isValidPrecondition match
         case true => Some(bitStream.readByte())
         case false => None()
   }

   def appendByteArray(arr: Array[UByte], noOfBytes: Int): Boolean = {
      val isValidPrecondition = bitStream.validate_offset_bytes(noOfBytes)
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      if isValidPrecondition then
         bitStream.appendByteArray(arr, noOfBytes)

      isValidPrecondition
   }


   def readByteArray(nBytes: Int): OptionMut[Array[UByte]] = {
      require(nBytes >= 0 && nBytes <= Integer.MAX_VALUE / NO_OF_BITS_IN_BYTE)
      require(bitStream.validate_offset_bytes(nBytes))

      val isValidPrecondition = bitStream.validate_offset_bytes(nBytes)
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      isValidPrecondition match
         case true => SomeMut(bitStream.readByteArray(nBytes))
         case false => NoneMut()
   }

   def readBits(nBits: Long): OptionMut[Array[UByte]] = {
      require(nBits >= 0 && bitStream.validate_offset_bits(nBits))

      val isValidPrecondition = bitStream.validate_offset_bits(nBits)
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      isValidPrecondition match
         case true => SomeMut(bitStream.readBits(nBits))
         case false => NoneMut()
   }

   def appendPartialByte(vVal: UByte, nBits: UByte): Boolean = {
      val isValidPrecondition = bitStream.validate_offset_bits(nBits)
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      if isValidPrecondition then
         bitStream.appendPartialByte(vVal, nBits)

      isValidPrecondition
   }

   def readPartialByte(nBits: Int): Option[UByte] = {
      require(nBits >= 0 && nBits <= NO_OF_BITS_IN_BYTE)
      require(bitStream.validate_offset_bits(nBits))

      val isValidPrecondition = bitStream.validate_offset_bits(nBits)
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      isValidPrecondition match
         case true => Some(bitStream.readPartialByte(nBits))
         case false => None()
   }

   def checkBitPatternPresent(bit_terminated_pattern: Array[UByte], nBits: Long): Option[Boolean] = {
      val isValidPrecondition = bitStream.validate_offset_bits(nBits)
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      isValidPrecondition match
         case true => Some(bitStream.checkBitPatternPresent(bit_terminated_pattern, nBits))
         case false => None()
   }

   // broken in C - do not translate
//   def readBits_nullterminated(bit_terminated_pattern: Array[UByte], bit_terminated_pattern_size_in_bits: Long, nMaxReadBits: Long): OptionMut[(Array[UByte], Long)] = {
//      val isValidPrecondition = bitStream.validate_offset_bits(nMaxReadBits)
//      assert(isValidPrecondition)
//      isValidPrecondition match
//         case true => SomeMut(bitStream.readBitsUntilTerminator(bit_terminated_pattern, bit_terminated_pattern_size_in_bits, nMaxReadBits))
//         case false => NoneMut()
//   }

   def alignToByte(): Boolean = {
      val isValidPrecondition = bitStream.validate_offset_bits(
         NO_OF_BITS_IN_BYTE - (bitStream.bitIndex() % NO_OF_BITS_IN_BYTE)
      )
      stainlessAssert(isValidPrecondition)
      runtimeAssert(isValidPrecondition)

      if isValidPrecondition then
         bitStream.alignToByte()

      isValidPrecondition
   }

   def alignToShort(): Unit = {
      // TODO: precondition
      bitStream.alignToShort()
//      alignToByte()
//      currentByte = ((currentByte + (NO_OF_BYTES_IN_JVM_SHORT - 1)) / NO_OF_BYTES_IN_JVM_SHORT) * NO_OF_BYTES_IN_JVM_SHORT
   }

   def alignToInt(): Unit = {
      // TODO: precondition
      bitStream.alignToInt()
//      alignToByte()
//      currentByte = ((currentByte + (NO_OF_BYTES_IN_JVM_INT - 1)) / NO_OF_BYTES_IN_JVM_INT) * NO_OF_BYTES_IN_JVM_INT
   }

   def resetBitIndex(): Unit = {
      bitStream.resetBitIndex()
   }

   def getBufferLength: Int = {
      bitStream.getBufferLength
   }

   /**
    *
    * @return the number of used bytes in the underlying buffer
    *         if the currentBit is not 0, currentByte is added by 1
    *
    */
   def getLength: Int = {
      bitStream.getLength
   }
}
