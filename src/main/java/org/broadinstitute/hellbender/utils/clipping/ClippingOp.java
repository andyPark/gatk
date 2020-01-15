package org.broadinstitute.hellbender.utils.clipping;

import com.google.common.annotations.VisibleForTesting;
import htsjdk.samtools.Cigar;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.CigarOperator;
import org.broadinstitute.hellbender.utils.Nucleotide;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.read.CigarUtils;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.read.ReadUtils;

import java.util.*;

/**
 * Represents a clip on a read.  It has a type (see the enum) along with a start and stop in the bases
 * of the read, plus an option extraInfo (useful for carrying info where needed).
 * <p/>
 * Also holds the critical apply function that actually execute the clipping operation on a provided read,
 * according to the wishes of the supplied ClippingAlgorithm enum.
 */
public final class ClippingOp {
    public final int start; // inclusive
    public final int stop;  // exclusive

    public ClippingOp(final int start, final int stop) {
        this.start = start;
        this.stop = stop;
    }


    public int getLength() {
        return stop - start;
    }

    /**
     * Clips the bases in read according to this operation's start and stop.  Uses the clipping
     * representation used is the one provided by algorithm argument.
     *  @param algorithm    clipping algorithm to use
     * @param originalRead the read to be clipped
     */
    public GATKRead apply(final ClippingRepresentation algorithm, final GATKRead originalRead) {
        switch (algorithm) {
            // important note:
            //   it's not safe to call read.getBases()[i] = 'N' or read.getBaseQualities()[i] = 0
            //   because you're not guaranteed to get a pointer to the actual array of bytes in the GATKRead
            case WRITE_NS: {
                final GATKRead readCopied = originalRead.copy();
                applyWriteNs(readCopied);
                return readCopied;
            }

            case WRITE_Q0S: {
                final GATKRead readCopied = originalRead.copy();
                applyWriteQ0s(readCopied);
                return readCopied;
            }

            case WRITE_NS_Q0S: {
                final GATKRead readCopied = originalRead.copy();
                applyWriteNs(readCopied);
                applyWriteQ0s(readCopied);
                return readCopied;
            }

            case HARDCLIP_BASES: {
                //Note: passing the original read here because the read is copied right away inside the method
                return applyHardClipBases(originalRead, start, stop);
            }

            case SOFTCLIP_BASES: {
                return applySoftClipBases(originalRead.copy());
            }

            case REVERT_SOFTCLIPPED_BASES: {
                return applyRevertSoftClippedBases(originalRead.copy());
            }

            default: {
                throw new IllegalStateException("Unexpected Clipping operator type " + algorithm);
            }
        }
    }

    private GATKRead applySoftClipBases(final GATKRead readCopied) {
        Utils.validateArg(!readCopied.isUnmapped(), "Read Clipper cannot soft clip unmapped reads");

        // BAM representation issue -- we can't softclip all bases in a read, so clip all but one
        final int myStop = Math.min(stop, start + readCopied.getLength() - 1);
        Utils.validate(start <= 0 || myStop == readCopied.getLength(), () -> String.format("Cannot apply soft clipping operator to the middle of a read: %s to be clipped at %d-%d", readCopied.getName(), start, myStop));

        final Cigar oldCigar = readCopied.getCigar();

        final Cigar newCigar = softClipCigar(oldCigar, start, myStop);
        readCopied.setCigar(newCigar);

        final int newClippedStart = getNewAlignmentStartOffset(newCigar, oldCigar);
        final int newStart = readCopied.getStart() + newClippedStart;
        readCopied.setPosition(readCopied.getContig(), newStart);
        return readCopied;
    }

    private void applyWriteQ0s(final GATKRead readCopied) {
        final byte[] newQuals = readCopied.getBaseQualities(); //this makes a copy so we can modify in place
        overwriteFromStartToStop(newQuals, (byte) 0);
        readCopied.setBaseQualities(newQuals);
    }

    private void applyWriteNs(final GATKRead readCopied) {
        final byte[] newBases = readCopied.getBases();       //this makes a copy so we can modify in place
        overwriteFromStartToStop(newBases, Nucleotide.N.encodeAsByte());
        readCopied.setBases(newBases);
    }

    private void overwriteFromStartToStop(final byte[] arr, final byte newVal) {
        Arrays.fill(arr, start, Math.min(arr.length, stop), newVal);
    }

    private GATKRead applyRevertSoftClippedBases(final GATKRead read) {
        GATKRead unclipped = read.copy();

        final Cigar unclippedCigar = new Cigar();
        int matchesCount = 0;
        for (final CigarElement element : read.getCigarElements()) {
            if (element.getOperator() == CigarOperator.SOFT_CLIP || element.getOperator() == CigarOperator.MATCH_OR_MISMATCH) {
                matchesCount += element.getLength();
            } else if (matchesCount > 0) {
                unclippedCigar.add(new CigarElement(matchesCount, CigarOperator.MATCH_OR_MISMATCH));
                matchesCount = 0;
                unclippedCigar.add(element);
            } else {
                unclippedCigar.add(element);
            }
        }
        if (matchesCount > 0) {
            unclippedCigar.add(new CigarElement(matchesCount, CigarOperator.MATCH_OR_MISMATCH));
        }

        unclipped.setCigar(unclippedCigar);
        final int newStart = read.getStart() + calculateAlignmentStartShift(read.getCigar(), unclippedCigar);

        if (newStart <= 0) {
            // if the start of the unclipped read occurs before the contig,
            // we must hard clip away the bases since we cannot represent reads with
            // negative or 0 alignment start values in the SAMRecord (e.g., 0 means unaligned)

            // We cannot set the read to temporarily have a negative start position, as our Read
            // interface will not allow it. Instead, since we know that the final start position will
            // be 1 after the hard clip operation, set it to 1 explicitly. We have to set it twice:
            // once before the hard clip (to reset the alignment stop / read length in read implementations
            // that cache these values, such as SAMRecord), and again after the hard clip.
            unclipped.setPosition(unclipped.getContig(), 1);
            unclipped = applyHardClipBases(unclipped, 0, -newStart + 1);

            // Reset the position to 1 again only if we didn't end up with an empty, unmapped read after hard clipping.
            // See https://github.com/broadinstitute/gatk/issues/3845
            if (!unclipped.isUnmapped()) {
                unclipped.setPosition(unclipped.getContig(), 1);
            }

            return unclipped;
        } else {
            unclipped.setPosition(unclipped.getContig(), newStart);
            return unclipped;
        }
    }

    /**
     * Given two cigar strings corresponding to read before and after soft-clipping, returns an integer
     * corresponding to the number of reference bases that the new string must be offset by in order to have the
     * correct start according to the reference.
     *
     * @param clippedCigar the new cigar string after clipping
     * @param oldCigar     the cigar string of the read before it was soft clipped
     */
    @VisibleForTesting
    static int getNewAlignmentStartOffset(final Cigar clippedCigar, final Cigar oldCigar) {
        int readBasesBeforeReference = 0; // The number of read bases consumed on the new cigar before reference bases are consumed

        int basesBeforeReferenceOld = 0; // The number of read bases consumed on the old cigar before reference bases are consumed
        int curReadCounter = 0; // A measure of the reference offset between the oldCigar and the clippedCigar

        for (final CigarElement e : clippedCigar.getCigarElements()) {
            if (!e.getOperator().consumesReferenceBases()) {
                if (e.getOperator().consumesReadBases()) {
                    readBasesBeforeReference += e.getLength();
                }
            } else {
                if (!e.getOperator().consumesReadBases()) {
                    basesBeforeReferenceOld -= e.getLength(); // Accounting for any D or N cigar operators at the front of the string
                } else {
                    break;
                }
            }
        }

        for (final CigarElement e : oldCigar.getCigarElements()) {
            int curReadLength = e.getLength();
            int curRefLength = e.getLength();
            if (!e.getOperator().consumesReadBases()) {
                curReadLength = 0;
            }

            final boolean truncated = curReadCounter + curReadLength > readBasesBeforeReference;


            if (truncated) {
                curReadLength = readBasesBeforeReference - curReadCounter;
                curRefLength = readBasesBeforeReference - curReadCounter;
            }

            if (!e.getOperator().consumesReferenceBases()) {
                curRefLength = 0;
            }

            curReadCounter += curReadLength;
            basesBeforeReferenceOld += curRefLength;

            if (curReadCounter > readBasesBeforeReference || truncated) {
                break;
            }
        }

        return Math.abs(basesBeforeReferenceOld); // if oldNum is negative it means some of the preceding N/Ds were trimmed but not all so we take absolute value
    }

    /**
     * Given a cigar string, soft clip up to leftClipEnd and soft clip starting at rightClipBegin
     */
    private Cigar softClipCigar(final Cigar cigar, final int start, final int stop) {
        final boolean clipLeft = start == 0;

        final Cigar newCigar = new Cigar();

        int elementStart = 0;
        for (final CigarElement element : cigar.getCigarElements()) {
            final CigarOperator operator = element.getOperator();
            // copy hard clips
            if (operator == CigarOperator.HARD_CLIP) {
                newCigar.add(new CigarElement(element.getLength(), element.getOperator()));
                continue;
            }
            final int elementEnd = elementStart + (operator.consumesReadBases() ? element.getLength() : 0);

            // element precedes start or follows end of soft clip, copy it to new cigar
            if (elementEnd <= start || elementStart >= stop) {
                newCigar.add(new CigarElement(element.getLength(), operator));
            } else {    // otherwise, some or all of the element is soft-clipped
                final int unclippedLength = clipLeft ? elementEnd - stop : start - elementStart;
                final int clippedLength = element.getLength() - unclippedLength;

                if (unclippedLength == 0) {
                    newCigar.add(new CigarElement(clippedLength, CigarOperator.SOFT_CLIP));
                } else if (clipLeft) {
                    newCigar.add(new CigarElement(clippedLength, CigarOperator.SOFT_CLIP));
                    newCigar.add(new CigarElement(unclippedLength, operator));
                } else {
                    newCigar.add(new CigarElement(unclippedLength, operator));
                    newCigar.add(new CigarElement(clippedLength, CigarOperator.SOFT_CLIP));
                }
            }
            elementStart = elementEnd;
        }

        return CigarUtils.combineAdjacentCigarElements(newCigar);
    }

    /**
     * Hard clip bases from read, from start to stop in base coordinates
     * <p>
     * If start == 0, then we will clip from the front of the read, otherwise we clip
     * from the right.  If start == 0 and stop == 10, this would clip out the first
     * 10 bases of the read.
     * <p>
     * Note that this function works with reads with negative alignment starts, in order to
     * allow us to hardClip reads that have had their soft clips reverted and so might have
     * negative alignment starts
     * <p>
     * Works properly with reduced reads and insertion/deletion base qualities
     * <p>
     * Note: this method does not assume that the read is directly modifiable
     * and makes a copy of it.
     *
     * @param read  a non-null read
     * @param start a start >= 0 and < read.length
     * @param stop  a stop >= 0 and < read.length.
     * @return a cloned version of read that has been properly trimmed down (Could be an empty, unmapped read)
     */
    private GATKRead applyHardClipBases(final GATKRead read, final int start, final int stop) {
        // If the read is unmapped there is no Cigar string and neither should we create a new cigar string

        final Cigar cigar = read.getCigar();//Get the cigar once to avoid multiple calls because each makes a copy of the cigar
        final CigarShift cigarShift = read.isUnmapped() ? new CigarShift(new Cigar(), 0, 0) : hardClipCigar(cigar, start, stop);

        // the cigar may force a shift left or right (or both) in case we are left with insertions
        // starting or ending the read after applying the hard clip on start/stop.
        final int newLength = read.getLength() - (stop - start) - cigarShift.shiftFromStart - cigarShift.shiftFromEnd;

        // If the new read is going to be empty, return an empty read now. This avoids initializing the new
        // read with invalid values below in certain cases (such as a negative alignment start).
        // See https://github.com/broadinstitute/gatk/issues/3466
        if (newLength == 0) {
            return ReadUtils.emptyRead(read);
        }

        final byte[] newBases = new byte[newLength];
        final byte[] newQuals = new byte[newLength];
        final int copyStart = (start == 0) ? stop + cigarShift.shiftFromStart : cigarShift.shiftFromStart;

        System.arraycopy(read.getBases(), copyStart, newBases, 0, newLength);
        System.arraycopy(read.getBaseQualities(), copyStart, newQuals, 0, newLength);

        final GATKRead hardClippedRead = read.copy();

        hardClippedRead.setBaseQualities(newQuals);
        hardClippedRead.setBases(newBases);
        hardClippedRead.setCigar(cigarShift.cigar);
        if (start == 0 && !read.isUnmapped()) {
            hardClippedRead.setPosition(read.getContig(), read.getStart() + calculateAlignmentStartShift(cigar, stop - start));
        }

        if (ReadUtils.hasBaseIndelQualities(read)) {
            final byte[] newBaseInsertionQuals = new byte[newLength];
            final byte[] newBaseDeletionQuals = new byte[newLength];
            System.arraycopy(ReadUtils.getBaseInsertionQualities(read), copyStart, newBaseInsertionQuals, 0, newLength);
            System.arraycopy(ReadUtils.getBaseDeletionQualities(read), copyStart, newBaseDeletionQuals, 0, newLength);
            ReadUtils.setInsertionBaseQualities(hardClippedRead, newBaseInsertionQuals);
            ReadUtils.setDeletionBaseQualities(hardClippedRead, newBaseDeletionQuals);
        }

        return hardClippedRead;

    }

    private CigarShift hardClipCigar(final Cigar cigar, final int start, final int stop) {
        final boolean clipLeft = start == 0;
        final int requestedHardClips = stop - start;

        // absorb any extant hard clips into those requested
        final int totalLeftHardClips = CigarUtils.countLeftHardClippedBases(cigar) + (clipLeft ? requestedHardClips : 0);
        final int totalRightHardClips = CigarUtils.countRightHardClippedBases(cigar) + (clipLeft ? 0 : requestedHardClips);

        final Cigar newCigar = new Cigar();
        newCigar.add(new CigarElement(totalLeftHardClips, CigarOperator.HARD_CLIP));

        int elementStart = 0;
        for (final CigarElement element : cigar.getCigarElements()) {
            final CigarOperator operator = element.getOperator();
            // hard clips have been absorbed separately
            if (operator == CigarOperator.HARD_CLIP) {
                continue;
            }
            final int elementEnd = elementStart + (operator.consumesReadBases() ? element.getLength() : 0);

            // element precedes start or follows end of hard clip, copy it to new cigar
            if (elementEnd <= start || elementStart >= stop) {
                newCigar.add(new CigarElement(element.getLength(), operator));
            } else {    // otherwise, some or all of the element is hard-clipped
                final int unclippedLength = clipLeft ? elementEnd - stop : start - elementStart;
                if (unclippedLength > 0) {
                    newCigar.add(new CigarElement(unclippedLength, operator));
                }
            }
            elementStart = elementEnd;
        }

        newCigar.add(new CigarElement(totalRightHardClips, CigarOperator.HARD_CLIP));
        return cleanHardClippedCigar(newCigar);
    }

    private enum Passes {
        FIRST,
        SECOND
    }

    /**
     * Checks if a hard clipped cigar left a read starting or ending with deletions or gap (N)
     * and cleans it up accordingly.
     *
     * @param cigar the original cigar
     * @return an object with the shifts (see CigarShift class)
     */
    private CigarShift cleanHardClippedCigar(final Cigar cigar) {

        final Cigar cleanCigar = new Cigar();
        int shiftFromStart = 0;
        int shiftFromEnd = 0;
        Stack<CigarElement> cigarStack = new Stack<>();
        final Stack<CigarElement> inverseCigarStack = new Stack<>();

        for (final CigarElement cigarElement : cigar.getCigarElements()) {
            cigarStack.push(cigarElement);
        }

        for (final Passes pass: Passes.values()) {
            final int shift = 0;
            int totalHardClip = 0;
            boolean readHasStarted = false;
            boolean addedHardClips = false;

            while (!cigarStack.empty()) {
                final CigarElement cigarElement = cigarStack.pop();

                if (!readHasStarted && cigarElement.getOperator() == CigarOperator.HARD_CLIP) {
                    totalHardClip += cigarElement.getLength();
                }

                // Deletions (D) and gaps (N) are not hardclips (H) and do not consume read bases....
                // so they gets dropped from the edges of the read since readHasStarted is still false.

                readHasStarted |= cigarElement.getOperator().consumesReadBases();

                if (readHasStarted) {
                    switch (pass) {
                        case FIRST:
                            if (!addedHardClips && totalHardClip > 0) {
                                inverseCigarStack.push(new CigarElement(totalHardClip, CigarOperator.HARD_CLIP));
                            }
                            inverseCigarStack.push(cigarElement);
                            break;
                        case SECOND:
                            if (!addedHardClips && totalHardClip > 0) {
                                cleanCigar.add(new CigarElement(totalHardClip, CigarOperator.HARD_CLIP));
                            }
                            cleanCigar.add(cigarElement);
                            break;
                    }
                    addedHardClips = true;
                }
            }
            switch (pass) {
                // first pass is from end to start of the cigar elements
                case FIRST:
                    shiftFromEnd = shift;
                    cigarStack = inverseCigarStack;
                    break;
                case SECOND:
                    // second pass is from start to end with the end already cleaned
                    shiftFromStart = shift;
                    break;
            }
        }

        return new CigarShift(cleanCigar, shiftFromStart, shiftFromEnd);
    }

    /**
     * Compute the offset of the first "real" position in the cigar on the genome
     * <p>
     * This is defined as a first position after a run of Hs followed by a run of Ss
     *
     * @param cigar A non-null cigar
     * @return the offset (from 0) of the first on-genome base
     */
    private int calcHardSoftOffset(final Cigar cigar) {
        final List<CigarElement> elements = cigar.getCigarElements();

        int size = 0;
        int i = 0;
        while (i < elements.size() && elements.get(i).getOperator() == CigarOperator.HARD_CLIP) {
            size += elements.get(i).getLength();
            i++;
        }
        while (i < elements.size() && elements.get(i).getOperator() == CigarOperator.SOFT_CLIP) {
            size += elements.get(i).getLength();
            i++;
        }

        return size;
    }

    /**
     * Calculates shift of alignment in the newCigar relative to the oldCigar due to
     * hard/soft clipping under assumption that the original clipped bases did not contain
     * insertions/deletions. This is a naive code that does not work for many CIGAR strings.
     *
     * @param oldCigar original cigar
     * @param newCigar new cigar (with hard/soft clipping)
     * @return int the offset (from 0 between the alignment start on the old and on the new cigar)
     */
    private int calculateAlignmentStartShift(final Cigar oldCigar, final Cigar newCigar) {
        final int newShift = calcHardSoftOffset(newCigar);
        final int oldShift = calcHardSoftOffset(oldCigar);
        return newShift - oldShift;
    }

    /**
     * Calculates how much the alignment should be shifted when hard/soft clipping is applied
     * to the cigar
     *
     * @param oldCigar            the original CIGAR
     * @param newReadBasesClipped - number of bases of the read clipped
     * @return int - the offset between the alignment starts in the oldCigar and in
     * the cigar after applying clipping
     */
    private int calculateAlignmentStartShift(final Cigar oldCigar, final int newReadBasesClipped) {

        int readBasesClipped = 0; // The number of read bases consumed on the new cigar before reference bases are consumed
        int refBasesClipped = 0; // A measure of the reference offset between the oldCigar and the clippedCigar

        final Iterator<CigarElement> iterator = oldCigar.getCigarElements().iterator();
        boolean truncated=false;

        while (iterator.hasNext()) {
            final CigarElement e = iterator.next();

            int curRefLength = e.getLength();
            int curReadLength = e.getOperator().consumesReadBases() ? e.getLength() : 0;


            truncated = readBasesClipped + curReadLength > newReadBasesClipped;
            if (truncated) {
                curReadLength = newReadBasesClipped - readBasesClipped;
                curRefLength = curReadLength;
            }

            if (!e.getOperator().consumesReferenceBases()) {
                curRefLength = 0;
            }

            readBasesClipped += curReadLength;
            refBasesClipped += curRefLength;

            if (readBasesClipped >= newReadBasesClipped || truncated) {
                break;
            }
        }

        // needed only if the clipping ended at a cigar element boundary and is followed by either N or D
        if (readBasesClipped == newReadBasesClipped && !truncated) {
            while (iterator.hasNext()) {
                final CigarElement e = iterator.next();

                if (e.getOperator().consumesReadBases() || !e.getOperator().consumesReferenceBases()) {
                    break;
                }

                refBasesClipped += e.getLength();
            }
        }

        return refBasesClipped;
    }

    private static final class CigarShift {
        private final Cigar cigar;
        private final int shiftFromStart;
        private final int shiftFromEnd;

        private CigarShift(final Cigar cigar, final int shiftFromStart, final int shiftFromEnd) {
            this.cigar = cigar;
            this.shiftFromStart = shiftFromStart;
            this.shiftFromEnd = shiftFromEnd;
        }
    }
}