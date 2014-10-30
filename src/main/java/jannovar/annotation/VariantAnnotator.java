package jannovar.annotation;

import jannovar.annotation.builders.BlockSubstitutionAnnotationBuilder;
import jannovar.annotation.builders.DeletionAnnotationBuilder;
import jannovar.annotation.builders.InsertionAnnotationBuilder;
import jannovar.annotation.builders.IntergenicAnnotationBuilder;
import jannovar.annotation.builders.IntronicAnnotationBuilder;
import jannovar.annotation.builders.NoncodingAnnotationBuilder;
import jannovar.annotation.builders.SingleNucleotideSubstitutionBuilder;
import jannovar.annotation.builders.SpliceAnnotationBuilder;
import jannovar.annotation.builders.UTRAnnotationBuilder;
import jannovar.common.VariantType;
import jannovar.exception.AnnotationException;
import jannovar.reference.Chromosome;
import jannovar.reference.TranscriptModel;
import jannovar.util.DNAUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Main driver class for annotating variants.
 *
 * @author Manuel Holtgrewe <manuel.holtgrewe@charite.de>
 * @author Marten Jaeger <marten.jaeger@charite.de>
 * @author Peter N Robinson <peter.robinson@charite.de>
 */
public class VariantAnnotator {
	// format string used for annotating SV inversions
	private static final String FORMAT_SV_INVERSION = "%s:g.%d_%dinv";
	// format string used for annotating SV insertions
	private static final String FORMAT_SV_INSERTION = "%s:g.%d_%dins%s..%s";
	// format string used for annotating SV deletions
	private static final String FORMAT_SV_DELETION = "%s:g.%d_%ddel";
	// format string used for annotating SV substitutions
	private static final String FORMAT_SV_SUBSTITUTION = "%s:g.%d_%ddelins%s..%s";

	/** Chromosomes with their TranscriptModel objects. */
	private HashMap<Byte, Chromosome> chromosomeMap = null;

	/**
	 * This object will be used to prioritize the annotations and to choose the one(s) to report. For instance, if we
	 * have both an intronic and a nonsense mutation, just report the nonsense mutation. Note that the object will be
	 * initialized once in the constructor of the Chromosome class and will be reset for each new annotation, rather
	 * than creating a new object for each variation. Also note that the constructor takes an integer value with which
	 * the lists of potential annotations get initialized. We will take 2*SPAN because this is the maximum number of
	 * annotations any variant can get with this program.
	 */
	private AnnotationCollector annovarFactory = null;

	/**
	 * The initial capacity for {@link jannovar.reference.TranscriptModel TranscriptModel} objects for the
	 */
	private static final int CAPACITY = 20;

	// TODO(holtgrew): we could make this threshold available through configuration
	/** The distance threshold in nucleotides for calling a variant upstream/downstream to a gene, */
	private static final int NEARGENE = 1000;

	/**
	 * Construct new VariantAnnotator, given a chromosome map.
	 *
	 * @param chromosomeMap
	 *            chromosome map to use for the annotator.
	 */
	public VariantAnnotator(HashMap<Byte, Chromosome> chromosomeMap) {
		this.chromosomeMap = chromosomeMap;
		this.annovarFactory = new AnnotationCollector(CAPACITY);
	}

	// TODO(holtgrem): Rename to buildAnnotationList()?
	/**
	 * Main entry point to getting Annovar-type annotations for a variant identified by chromosomal coordinates. When we
	 * get to this point, the client code has identified the right chromosome, and we are provided the coordinates on
	 * that chromosome.
	 *
	 * The strategy for finding annotations is based on the perl code in Annovar. Roughly speaking, we identify a list
	 * of genes affected by the variant using the interval tree ({@link #itree}) and then annotated the variants
	 * accordingly. If no hit is found in the tree, we identify the left and right neighbor and annotate to intergenic,
	 * upstream or downstream
	 *
	 * @param position
	 *            The start position of the variant on this chromosome (one-based numbering)
	 * @param ref
	 *            String representation of the reference sequence affected by the variant
	 * @param alt
	 *            String representation of the variant (alt) sequence
	 * @return a list of {@link jannovar.annotation.Annotation Annotation} objects corresponding to the mutation
	 *         described by the object (often just one annotation, but potentially multiple ones).
	 * @throws jannovar.exception.AnnotationException
	 */
	public AnnotationList getAnnotationList(byte c, int position, String ref, String alt)
			throws AnnotationException {
		// Get chromosome by id.
		Chromosome chr = chromosomeMap.get(c);
		if (chr == null) {
			String e = String.format("Could not identify chromosome \"%d\"", c);
			throw new AnnotationException(e);
		}

		/* The following command "resets" the annovarFactory object */
		this.annovarFactory.clearAnnotationLists();

		// Define start and end positions of variant
		int start = position;
		int end = start + ref.length() - 1;

		// TODO(holtgrem): don't we have use intervals? update comment below
		// Get the TranscriptModel objects that overlap with (start, end).
		ArrayList<TranscriptModel> candidateTranscripts = chr.getItree().search(start, end);

		// for structural variants we also perform a big intervals search
		boolean isStructuralVariant = false;
		if ((ref.length() >= 1000 || alt.length() >= 1000)) {
			if (ref.length() >= 1000)
				candidateTranscripts.addAll(chr.getItree().searchBigInterval(start, end));
			isStructuralVariant = true;
		}

		// Handle the case of no overlapping transcript and create intergenic, upstream, or downstream annotations
		if (candidateTranscripts.isEmpty()) {
			if (isStructuralVariant)
				getStructuralVariantAnnotation(position, ref, alt, null);
			else
				createIntergenicAnnotations(start, end, chr.getItree().getLeftNeighbor(), chr.getItree().getRightNeighbor());
			return annovarFactory.getAnnotationList();
		}

		// If we get here, then there is at least one transcript that overlaps with the query. Iterate over these
		// transcripts and collect annotations for each (they are collected in annovarFactory).
		for (TranscriptModel tm : candidateTranscripts) {
			if (isStructuralVariant) {
				getStructuralVariantAnnotation(position, ref, alt, tm);
			} else {
				if (tm.isPlusStrand())
					getPlusStrandAnnotation(position, ref, alt, tm);
				else if (tm.isMinusStrand())
					getMinusStrandAnnotation(position, ref, alt, tm);
			}
		}

		// Obtain annotation list from annovarFactory, if we could not find any annotations then this is a logical
		// error.
		// TODO(holtgrew): Throw unchecked exception instead? This no annotations here would be a bug!
		AnnotationList al = annovarFactory.getAnnotationList();
		if (al.getAnnotationList().isEmpty()) {
			String e = String.format("[Jannovar:Chromosome] Error: No annotations produced for %s:g.%d%s>%s",
					chr.getChromosomeName(), position, ref, alt);
			throw new AnnotationException(e);
		}
		return al;
	}

	/**
	 * Main entry point to getting structural variant annotations for a variant identified by chromosomal coordinates
	 * for a Variant bigger than 1000bp. This will create either SV_INSERTION, SV_DELETIONS or SV_SUBSTITUTION type
	 * Annotations.
	 *
	 * @param position
	 *            The start position of the variant on this chromosome
	 * @param ref
	 *            String representation of the reference sequence affected by the variant
	 * @param alt
	 *            String representation of the variant (alt) sequence
	 * @param tm
	 *            associated {@link TranscriptModel}
	 * @throws AnnotationException
	 */
	private void getStructuralVariantAnnotation(int position, String ref, String alt, TranscriptModel tm)
			throws AnnotationException {
		Annotation anno;
		String annoText;

		if (ref.length() == alt.length() && ref.equals(new StringBuilder(alt).reverse())) { // SV inversion
			// TODO(holtgrem): we should care about breakpoints within genes
			annoText = String.format(FORMAT_SV_INVERSION, VariantType.SV_INVERSION, position, position + ref.length(),
					alt.substring(0, 2), alt.substring(alt.length() - 2, alt.length()));
			anno = new Annotation(tm, annoText, VariantType.SV_INVERSION);
		} else if (ref.length() == 1) { // SV insertion
			// if tm is null it is intergenic
			if (tm == null) {
				annoText = String.format(FORMAT_SV_INSERTION, VariantType.INTERGENIC, position, position + 1,
						alt.substring(0, 2), alt.substring(alt.length() - 2, alt.length()));
				anno = new Annotation(tm, annoText, VariantType.INTERGENIC);
			} else {
				annoText = String.format(FORMAT_SV_INSERTION, tm.getChromosomeAsString(), position, position + 1,
						alt.substring(0, 2), alt.substring(alt.length() - 2, alt.length()));
				anno = new Annotation(tm, annoText, VariantType.SV_INSERTION);
			}
		} else if (alt.length() == 1) { // SV deletion
			// if tm is null it is intergenic
			if (tm == null) {
				annoText = String.format(FORMAT_SV_DELETION, VariantType.INTERGENIC, position, position + ref.length());
				anno = new Annotation(tm, annoText, VariantType.INTERGENIC);
			} else {
				annoText = String.format(FORMAT_SV_DELETION, tm.getChromosomeAsString(), position,
						position + ref.length());
				anno = new Annotation(tm, annoText, VariantType.SV_DELETION);
			}
		} else { // SV substitution
			// if tm is null it is intergenic
			if (tm == null) {
				annoText = String.format(FORMAT_SV_SUBSTITUTION, VariantType.INTERGENIC, position,
						position + ref.length(), alt.substring(0, 2), alt.substring(alt.length() - 2, alt.length()));
				anno = new Annotation(tm, annoText, VariantType.INTERGENIC);
			} else {
				annoText = String.format(FORMAT_SV_SUBSTITUTION, tm.getChromosomeAsString(), position,
						position + ref.length(), alt.substring(0, 2), alt.substring(alt.length() - 2, alt.length()));
				anno = new Annotation(tm, annoText, VariantType.SV_SUBSTITUTION);
			}
		}

		annovarFactory.addStructuralAnnotation(anno);
	}

	// TODO(holtgrem): can this be removed?
	/**
	 * Counts the number of affected genes by different GeneSymbol. Returns <code>true</code> if there are more than one
	 * gensymbols in the candidateGenes list or <code>false</code> otherwise.
	 *
	 * @param candidateGenes
	 * @return <code>true</code> for multiple genes in the list
	 * @deprecated
	 */
	@Deprecated
	private boolean isMultiGeneAffecting(ArrayList<TranscriptModel> candidateGenes) {
		HashSet<String> symbols = new HashSet<String>();
		for (TranscriptModel model : candidateGenes) {
			symbols.add(model.getGeneSymbol());
		}
		if (symbols.size() > 1)
			return true;
		else
			return false;
	}

	/**
	 * This function is called when the query position(s) represented by start and end do not overlap with any isoforms.
	 * In this case, it must be integenic. UPSTREAM and DOWNSTREAM annotations are special cases of INTERGENIC when the
	 * variant is within 1000 nucleotides of a transcript.
	 *
	 * @param start
	 *            The start position of the variant on this chromosome
	 * @param end
	 *            The end position of the variant on this chromosome
	 * @param leftNeighbor
	 *            The transcript that is closest to the variant on the left
	 * @param rightNeighbor
	 *            The transcript that is closest to the variant on the right
	 */
	private void createIntergenicAnnotations(int start, int end, TranscriptModel leftNeighbor,
			TranscriptModel rightNeighbor) {

		/* ***************************************************************************************** *
		 * The following code block is executed if the variant has not hit a genic region yet and * it basically updates
		 * information about the nearest 5' (left) and 3' (right) neighbor. * This information is useful for
		 * "intergenic" variants. *
		 * *****************************************************************************************
		 */
		/*
		 * System.out.println("createIntergenicAnnotations: " + start + "-" + end); if (leftNeighbor == null) {
		 * System.out.println("leftNeighbor == null"); } else { System.out.println("L: " + leftNeighbor); } if
		 * (rightNeighbor == null) { System.out.println("rightNeighbor == null"); } else { System.out.println("R: " +
		 * rightNeighbor); }
		 */

		if (leftNeighbor != null && leftNeighbor.isNearThreePrimeEnd(start, NEARGENE)) {
			/** The following function creates an upstream or downstream annotation as appropriate. */
			Annotation ann = IntergenicAnnotationBuilder.createUpDownstreamAnnotation(leftNeighbor, start);
			annovarFactory.addUpDownstreamAnnotation(ann);
		}

		if (rightNeighbor != null && rightNeighbor.isNearFivePrimeEnd(end, NEARGENE)) {
			/** The following function creates an upstream or downstream annotation as appropriate. */
			Annotation ann = IntergenicAnnotationBuilder.createUpDownstreamAnnotation(rightNeighbor, end);
			annovarFactory.addUpDownstreamAnnotation(ann);
		}
		// If we get here, and annovarFactory is still empty, then the variant is not nearby to any gene (i.e., it is
		// not upstream/downstream). Therefore, the variant is considered intergenic.
		if (annovarFactory.isEmpty()) {
			if (leftNeighbor == null && rightNeighbor == null) {
				// TODO(holtgrem): here, we should throw an unchecked exception, createInterneicAnnotation would throw a
				// NullPointerException
				System.out.println("Both neighbors are null");
			}
			Annotation ann = IntergenicAnnotationBuilder.createIntergenicAnnotation(leftNeighbor, rightNeighbor, start,
					end);
			annovarFactory.addIntergenicAnnotation(ann);
		}
	}

	/**
	 * Main entry point to getting Annovar-type annotations for a variant identified by chromosomal coordinates for a
	 * TranscriptModel that is transcribed from the plus strand. This could theoretically be combined with the Minus
	 * strand functionalities, but separating them makes things easier to comprehend and debug.
	 *
	 * @param position
	 *            The start position of the variant on this chromosome
	 * @param ref
	 *            String representation of the reference sequence affected by the variant
	 * @param alt
	 *            String representation of the variant (alt) sequence
	 * @param tm
	 *            associated {@link TranscriptModel}
	 * @throws AnnotationException
	 */
	private void getPlusStrandAnnotation(int position, String ref, String alt, TranscriptModel tm)
			throws AnnotationException {

		/*
		 * System.out.println(String.format("getPLusStrand for %s [%s] at position=%d, ref=%s, alt=%s",
		 * tm.getGeneSymbol(),tm.getName(),position,ref,alt));
		 */

		// int txstart = tm.getTXStart();
		// int txend = tm.getTXEnd();
		int cdsstart = tm.getCDSStart();
		int cdsend = tm.getCDSEnd();
		int exoncount = tm.getExonCount();
		// String name2 = tm.getGeneSymbol(); /* the gene symbol */
		// String name = tm.getName(); /* the ucsc knowngene id */
		int start = position;
		int end = start + ref.length() - 1;

		int cumlenintron = 0; // cumulative length of introns at a given exon
		int cumlenexon = 0; // cumulative length of exons at a given exon
		int rvarstart = -1; // start of variant within reference RNA sequence
		int rvarend = -1; // end of variant within reference RNA sequence
		// boolean foundexonic=false; // have we found the variant to lie in an exon yet?
		// int rcdsstart = tm.getRefCDSStart(); // start of CDS within reference RNA sequence.

		for (int k = 0; k < exoncount; ++k) {
			// System.out.println("getPlusStrandCodingSequenceAnnotation exon " + k);
			if (k > 0)
				cumlenintron += tm.getLengthOfIntron(k);
			cumlenexon += tm.getLengthOfExon(k);
			if (cdsstart >= tm.getExonStart(k) && cdsstart <= tm.getExonEnd(k)) {
				/* "cdsstart" is thus contained within this exon */
				cumlenexon = tm.getExonEnd(k) - cdsstart + 1;
			}
			/* 1) First check whether variant is a splice variant */
			// System.out.println("BLA, About to check for splice for gene " + tm.getGeneSymbol());
			// isSpliceVariantPositiveStrand(TranscriptModel tm, int start, int end, String ref, String alt, int k) {
			if (SpliceAnnotationBuilder.isSpliceVariant(tm, start, end, ref, alt, k)) {
				Annotation ann = SpliceAnnotationBuilder.getSpliceAnnotationPlusStrand(tm, start, end, ref, alt, k,
						cumlenexon);
				if (tm.isCodingGene()) {
					annovarFactory.addExonicAnnotation(ann);
				} else {
					ann.setVarType(VariantType.ncRNA_SPLICING);
					annovarFactory.addNcRNASplicing(ann);
				}
				return; // we are done with this variant/TranscriptModel combination.
			}
			if (start < tm.getExonStart(k)) {
				// System.out.println(String.format("BLA, start=%d, end=%d,exon[%d] start=%d for gene %s ",
				// start,end,k,tm.getExonStart(k), tm.getGeneSymbol()));
				/*
				 * --------------------------------------------------------------------------- * The variant is not a
				 * splice mutation (because of the above code), and it * begins before the start position of exon k.
				 * Therefore, there are several * possibilities. 1) It overlaps with the start of exon k (then, we have
				 * that * end >=exonstart(k). It could be in the 5'UTR, 3UTR, or a coding exon. *
				 * ---------------------------------------------------------------------------
				 */
				if (end >= tm.getExonStart(k)) {
					/* Overlap: Variation starts 5' to exon and ends within exon */
					/* 1) Get the start position of the variant w.r.t. transcript (rvarstart) */
					/* $rvarstart = $exonstart[$k]-$txstart-$lenintron+1; */
					rvarstart = tm.getExonStart(k) - tm.getTXStart() - cumlenintron + 1;
					// System.out.println("1 HERE rvarstart os " + rvarstart);
					/* 2) Get the end position of the variant w.r.t. the transcript (rvarend) */
					rvarend = tm.getRVarEnd(end, k, cumlenintron);
					if (end < cdsstart && tm.isCodingGene()) {
						/*
						 * 3) Variant disrupts/changes 5' UTR region. Rarely, if the 5' UTR is also separated by
						 * introns, the variant is more complex. #query ---- #gene <--*---*->
						 */
						Annotation ann = UTRAnnotationBuilder.createUTR5Annotation(tm, rvarstart, ref, alt);
						// Annotation.createUTR5Annotation(tm,rvarstart,ref,alt);
						annovarFactory.addUTR5Annotation(ann);

						/*
						 * Annovar: $utr5{$name2}++; positive strand for UTR5
						 */
					} else if (start > cdsend && tm.isCodingGene()) {
						/*
						 * 4) The variant disrupts/changes 3' UTR region #query ---- #gene <--*---*->
						 */
						// Annotation ann = UTRAnnotation.getUTR3Annotation(tm,start,end,ref,alt);
						Annotation ann = UTRAnnotationBuilder.createUTR3Annotation(tm, rvarstart, ref, alt);
						annovarFactory.addUTR3Annotation(ann);

						/* positive strand for UTR3 */
					} else {
						/*
						 * 5) If we get here, the variant is located within an exon. Note k in the following is the
						 * number (zero-based) of affected exon
						 */
						annotateExonicVariants(rvarstart, rvarend, start, end, ref, alt, k, tm);
					}
					break; /* break out of for loop of exons (k) */

				} else if (k > 0 && start > tm.getExonEnd(k - 1)) {
					/*
					 * ----------------------------------------------------------------------- * if we get here, then
					 * start < exonstart(k) and also start > exonend(k-1) * This means that the variant is INTRONIC of a
					 * coding or noncoding RNA * -----------------------------------------------------------------------
					 */
					Annotation ann = null;
					if (tm.isCodingGene()) {
						ann = IntronicAnnotationBuilder.createIntronicAnnotation(tm, k, start, end, ref, alt);
					} else {
						ann = IntronicAnnotationBuilder.createNcRNAIntronicAnnotation(tm, k, start, end, ref, alt);
					}
					annovarFactory.addIntronicAnnotation(ann);
					return; /* Done with this annotation */
				}
			} /* end; if (start < tm.getExonStart(k)) */
			else if (start <= tm.getExonEnd(k)) {
				/*
				 * ----------------------------------------------------------------------- * If we get here, then the
				 * start >= exonstart(k) and start <=exonend(k). * Thus, the start of the variant is located within exon
				 * k. The following * code then calculates the start (rvarstart) and end (rvarend) position * of the
				 * variant within the RNA. * -----------------------------------------------------------------------
				 */
				/* $rvarstart = $start-$txstart-$lenintron+1; */
				rvarstart = start - tm.getTXStart() - cumlenintron + 1;
				rvarend = tm.getRVarEnd(end, k, cumlenintron);
				/*
				 * ----------------------------------------------------------------------- * We now search for the end
				 * of the mutation. We know that we have found * the end when 1) end < exonstart(m) TODO CHEKC THIS or
				 * 2) end<exonend(m) * In the latter case, both the start and the end of the mutation are * located
				 * within exon k. We can then break out of the for loop after we * have calculated rvarstart and
				 * rvarend. * -----------------------------------------------------------------------
				 */
				for (int m = k; m < tm.getExonCount(); ++m) {
					if (m > k) {
						cumlenintron += tm.getLengthOfIntron(m);
					}
					if (end < tm.getExonStart(m)) {
						// #query ------
						// #gene <--**---******---****---->
						rvarend = tm.getExonEnd(m - 1) - tm.getTXStart() - cumlenintron + 1
								+ tm.getLengthOfIntron(m - 1);
						// $rvarend = $exonend[$m-1]-$txstart-$lenintron+1 + ($exonstart[$m]-$exonend[$m-1]-1);
						break;
					} else if (end <= tm.getExonEnd(m)) {
						// #query -----------
						// #gene <--**---******---****---->
						// $rvarend = $end-$txstart-$lenintron+1;
						rvarend = end - tm.getTXStart() - cumlenintron + 1;
						break; // last;
					}
				}
				/*
				 * if (not defined $rvarend) { $rvarend = $txend-$txstart-$lenintron+1; #if this value is longer than
				 * transcript length, it suggest whole gene deletion }
				 */
				if (rvarend < 0) { // i.e., uninitialized
					rvarend = end - tm.getTXStart() - cumlenintron + 1;
				}

				/*
				 * ------------------------------------------------------------------------- * If we get here, the
				 * variant is located somewhere in a exon. There are * several possibilities: 1) Noncoding RNA, 2) UTR5,
				 * 3) UTR3, 4) Exonic in * a coding gene within the actual coding sequence (not UTR). *
				 * -------------------------------------------------------------------------
				 */
				if (tm.isNonCodingGene()) {
					// ref = DNAUtils.reverseComplement(ref);
					// alt = DNAUtils.reverseComplement(alt);
					Annotation ann = NoncodingAnnotationBuilder.createNoncodingExonicAnnotation(tm, rvarstart, ref,
							alt, k);
					annovarFactory.addNonCodingRNAExonicAnnotation(ann);
				} else if (end < cdsstart) {
					/*
					 * #usually disrupt/change 5' UTR region, unless the UTR per se is also separated by introns #query
					 * ---- #gene <--*---*-> Annovar: $utr5{$name2}++; #positive strand for UTR5
					 */
					Annotation ann = UTRAnnotationBuilder.createUTR5Annotation(tm, rvarstart, ref, alt);
					annovarFactory.addUTR5Annotation(ann);

				} else if (start > cdsend) {
					/*
					 * #query ---- #gene <--*---*-> Annovar: $utr3{$name2}++; #positive strand for UTR3
					 */
					// Annotation ann = UTRAnnotation.getUTR3Annotation(tm,start,end,ref,alt);
					Annotation ann = UTRAnnotationBuilder.createUTR3Annotation(tm, rvarstart, ref, alt);
					annovarFactory.addUTR3Annotation(ann);
				} else {
					/* Note that the following function adds annotations to annovar */
					annotateExonicVariants(rvarstart, rvarend, start, end, ref, alt, k + 1, tm);
				}
			}
		} /* iterator over exons */
	}

	/**
	 * Main entry point to getting Annovar-type annotations for a variant identified by chromosomal coordinates for a
	 * TranscriptModel that is transcribed from the minus strand. This could theoretically be combined with the Minus
	 * strand functionalities, but separating them makes things easier to comprehend and debug.
	 *
	 * @param position
	 *            The start position of the variant on this chromosome
	 * @param ref
	 *            String representation of the reference sequence affected by the variant
	 * @param alt
	 *            String representation of the variant (alt) sequence
	 * @param tm
	 *            assigned {@link TranscriptModel}
	 * @throws jannovar.exception.AnnotationException
	 */
	private void getMinusStrandAnnotation(int position, String ref, String alt, TranscriptModel tm)
			throws AnnotationException {

		/*
		 * System.out.println(String.format("BLA, getMinusString: %s[%s], position=%d, ref=%s, alt=%s",
		 * tm.getGeneSymbol(),tm.getName() ,position,ref,alt));
		 */

		int txstart = tm.getTXStart();
		int txend = tm.getTXEnd();
		int cdsstart = tm.getCDSStart();
		int cdsend = tm.getCDSEnd();
		int exoncount = tm.getExonCount();
		// String name2 = tm.getGeneSymbol(); /* the gene symbol */
		// String name = tm.getName(); /* the ucsc knowngene id */
		int start = position;
		int end = start + ref.length() - 1;

		int cumlenintron = 0; // cumulative length of introns at a given exon
		int cumlenexon = 0; // cumulative length of exons at a given exon
		int rvarstart = -1; // start of variant within reference RNA sequence
		int rvarend = -1; // end of variant within reference RNA sequence
		// boolean foundexonic=false; // have we found the variant to lie in an exon yet?
		// int rcdsstart = tm.getRefCDSStart(); // start of CDS within reference RNA sequence.

		/***************************************************************************************
		 * * Iterate over all exons of the gene. Start with the 3'-most exon, which is the first * exon for genes
		 * transcribed from the minus strand. *
		 * *************************************************************************************
		 */
		for (int k = exoncount - 1; k >= 0; k--) {
			if (k < exoncount - 1) {
				cumlenintron += tm.getExonStart(k + 1) - tm.getExonEnd(k) - 1;
			}
			cumlenexon += tm.getExonEnd(k) - tm.getExonStart(k) + 1;
			if (cdsend <= tm.getExonEnd(k)) { // calculate CDS start accurately by considering intron length
				// rcdsstart = txend-cdsend-cumlenintron+1;
				if (cdsend >= tm.getExonStart(k)) { // CDS start within this exon
					cumlenexon = cdsend - tm.getExonStart(k) + 1;
				}
			}

			/* 1) First check whether variant is a splice variant */
			// System.out.println("BLA, About to check for splice for gene " + tm.getGeneSymbol());
			if (SpliceAnnotationBuilder.isSpliceVariant(tm, start, end, ref, alt, k)) {
				Annotation ann = SpliceAnnotationBuilder.getSpliceAnnotationMinusStrand(tm, start, end, ref, alt, k,
						cumlenexon);
				if (tm.isCodingGene()) {
					annovarFactory.addExonicAnnotation(ann);
				} else {
					ann.setVarType(VariantType.ncRNA_SPLICING);
					annovarFactory.addNcRNASplicing(ann);
				}
				return; /* We are done with this annotation. */
			}
			/*
			 * --------------------------------------------------------------------------- * The variant is not a splice
			 * mutation (because of the above code), and it * begins after the end position of exon k on the minus
			 * strand. Therefore, * there are several possibilities. 1) It overlaps with the end of exon k * (then, we
			 * have that start >=exonend(k). It could be in the 5'UTR, 3UTR, or * a coding exon. *
			 * ---------------------------------------------------------------------------
			 */
			if (end > tm.getExonEnd(k)) {
				if (start <= tm.getExonEnd(k)) {
					/* Overlap: Variation starts 5' to exon and ends within exon */
					rvarstart = tm.getTXEnd() - tm.getExonEnd(k) - cumlenintron + 1;
					// $rvarstart = $txend-$exonend[$k]-$lenintron+1;
					for (int m = k; m >= 0; m--) {
						if (m < k)
							cumlenintron += tm.getExonStart(m + 1) - tm.getExonEnd(m) - 1;
						// $m < $k and $lenintron += ($exonstart[$m+1]-$exonend[$m]-1);
						if (start > tm.getExonEnd(m)) {
							// query --------
							// gene <--**---******---****---->
							// $rvarend = $txend-$exonstart[$m+1]+1-$lenintron + ($exonstart[$m+1]-$exonend[$m]-1);
							// #fixed this 2011feb18
							rvarend = tm.getTXEnd() - tm.getExonStart(m + 1) + 1 - cumlenintron
									+ (tm.getExonStart(m + 1) - tm.getExonEnd(m) - 1);
							break;
						} else if (start >= tm.getExonStart(m)) {
							// query ----
							// gene <--**---******---****---->
							// $rvarend = $txend-$start-$lenintron+1;
							rvarend = tm.getTXEnd() - start - cumlenintron + 1;
							break;
						}
					}
					if (rvarend < 0) {
						// if rvarend is not found, then the whole tail of gene is covered
						rvarend = tm.getTXEnd() - tm.getTXStart() - cumlenintron + 1;
					}
					/*
					 * ------------------------------------------------------------- * When we get here, the variant
					 * overlaps an exon. It can be an * exon of a noncoding gene, a UTR5, UTR3, or a CDS exon. *
					 * -------------------------------------------------------------
					 */
					if (tm.isNonCodingGene()) {
						/* For now, annotation the ncRNA vars with just the gene symbol */
						String annot = tm.getAccessionNumber();
						Annotation ann = new Annotation(tm, annot, VariantType.ncRNA_EXONIC);
						annovarFactory.addNonCodingRNAExonicAnnotation(ann);
						return;
					} else if (end < cdsstart) {
						// query ----
						// gene <--*---*->
						// Note this is UTR3 on negative strand
						alt = DNAUtils.reverseComplement(alt);
						ref = DNAUtils.reverseComplement(ref);
						Annotation ann = UTRAnnotationBuilder.createUTR3Annotation(tm, rvarstart, ref, alt);
						annovarFactory.addUTR3Annotation(ann);
						return; /* done with this annotation. */
					} else if (start > cdsend) {
						// query ----
						// gene <--*---*->
						// Note this is UTR5 on negative strand
						alt = DNAUtils.reverseComplement(alt);
						ref = DNAUtils.reverseComplement(ref);
						Annotation ann = UTRAnnotationBuilder.createUTR5Annotation(tm, rvarstart, ref, alt);
						annovarFactory.addUTR5Annotation(ann);
						return; /* done with this annotation. */
					} else {
						/*
						 * ------------------------------------------------------------- * If we get here, the variant
						 * is located within a CDS exon. * In difference to annovar, we do not distinguish here whether
						 * * the variant is coding or noncoding, that will be done in the * following function. Note k
						 * in the following is the number * (zero-based) of affected exon *
						 * -------------------------------------------------------------
						 */
						annotateExonicVariants(rvarstart, rvarend, start, end, ref, alt, k, tm);
						return; /* done with this annotation. */
					}
				} else if (k < tm.getExonCount() - 1 && end < tm.getExonStart(k + 1)) {
					// System.out.println("- gene intron tm=" + tm.getGeneSymbol() + ":" + tm.getName());
					Annotation ann;
					alt = DNAUtils.reverseComplement(alt);
					ref = DNAUtils.reverseComplement(ref);
					if (tm.isCodingGene()) {
						ann = IntronicAnnotationBuilder.createIntronicAnnotation(tm, k, start, end, ref, alt);
					} else {
						ann = IntronicAnnotationBuilder.createNcRNAIntronicAnnotation(tm, k, start, end, ref, alt);
					}
					annovarFactory.addIntronicAnnotation(ann);
					return; /* done with this annotation. */
				}
			} /* end; if (end > tm.getExonEnd(k)) */
			else if (end >= tm.getExonStart(k)) {
				// rvarstart is with respect to cDNA sequence (so rvarstart corresponds to end of variants)
				rvarstart = txend - end - cumlenintron + 1;
				for (int m = k; m >= 0; m--) {
					if (m < k)
						cumlenintron += tm.getExonStart(m + 1) - tm.getExonEnd(m) - 1;// length of intron
					if (start > tm.getExonEnd(m)) {
						// query ----
						// gene <--**---******---****---->
						rvarend = txend - tm.getExonStart(m + 1) + 1 - cumlenintron
								+ (tm.getExonStart(m + 1) - tm.getExonEnd(m) - 1);
						break; // finish the cycle of counting exons!!!!!
					} else if (start >= tm.getExonStart(m)) { // the start is right located within exon
						// query -------
						// gene <--**---******---****---->
						rvarend = txend - start - cumlenintron + 1;
						break; // finish the cycle
					}
				}
				if (rvarend < 0) { // i.e., rvarend not initialized, then the whole tail of gene is covered
					rvarend = txend - txstart - cumlenintron + 1;
				}

				/*
				 * ------------------------------------------------------------------------- * If we get here, the
				 * variant is located somewhere in a exon. There are * several possibilities: 1) Noncoding RNA, 2) UTR5,
				 * 3) UTR3, 4) Exonic in * a coding gene within the actual coding sequence (not UTR). *
				 * -------------------------------------------------------------------------
				 */
				if (tm.isNonCodingGene()) {
					ref = DNAUtils.reverseComplement(ref);
					alt = DNAUtils.reverseComplement(alt);
					Annotation ann = NoncodingAnnotationBuilder.createNoncodingExonicAnnotation(tm, rvarstart, ref,
							alt, k);
					annovarFactory.addNonCodingRNAExonicAnnotation(ann);
					return; /* done with this annotation. */
				} else if (end < cdsstart) {
					/* Negative strand, mutation located 5' to CDS start, i.e., 3UTR */
					// query ----
					// gene <--*---*->
					ref = DNAUtils.reverseComplement(ref);
					alt = DNAUtils.reverseComplement(alt);
					Annotation ann = UTRAnnotationBuilder.createUTR3Annotation(tm, rvarstart, ref, alt);
					annovarFactory.addUTR3Annotation(ann);
					return; /* done with this annotation. */
				} else if (start > cdsend) {
					/* Negative strand, mutation located 3' to CDS end, i.e., 5UTR */
					// query ----
					// gene <--*---*->
					// System.out.println(String.format("start:%d, cdsend:%d, gene:%s",start,cdsend,tm.getGeneSymbol()));
					ref = DNAUtils.reverseComplement(ref);
					alt = DNAUtils.reverseComplement(alt);
					Annotation ann = UTRAnnotationBuilder.createUTR5Annotation(tm, rvarstart, ref, alt);
					annovarFactory.addUTR5Annotation(ann);
				} else {
					annotateExonicVariants(rvarstart, rvarend, start, end, ref, alt, exoncount - k, tm);
				}
			}
		} /* iterator over exons */
	}

	/**
	 * This method corresponds to Annovar function {@code sub annotateExonicVariants} { my ($refseqvar, $geneidmap,
	 * $cdslen, $mrnalen) = @_; (...)
	 * <P>
	 * Finally, the $refseqvar in Annovar has the following pieces of information {@code my ($refcdsstart, $refvarstart,
	 * $refvarend, $refstrand, $index, $exonpos, $nextline) = @ $refseqvar-> $seqid}->[$i]};} Note that refcdsstart and
	 * refstrand are contained in the TranscriptModel objects
	 *
	 * @param refvarstart
	 *            The start position of the variant with respect to the CDS of the mRNA
	 * @param refvarend
	 *            The end position of the variant with respect to the CDS of the mRNA
	 * @param start
	 *            chromosomal start position of variant
	 * @param end
	 *            chromosomal end position of variant
	 * @param ref
	 *            sequence of reference
	 * @param var
	 *            sequence of variant
	 * @param exonNumber
	 *            Number (zero-based) of affected exon.
	 * @param tm
	 *            Gene in which variant was localized to one of the exons
	 */
	private void annotateExonicVariants(int refvarstart, int refvarend, int start, int end, String ref, String var,
			int exonNumber, TranscriptModel tm) throws AnnotationException {
		// System.out.println();
		// System.out.println("refvarstart: " + refvarstart);
		// System.out.println("refvarend: " + refvarend);
		// System.out.println("start: " + start);
		// System.out.println("end: " + end);
		// System.out.println("ref: " + ref);
		// System.out.println("var/alt: " + var);
		// System.out.println("exonNr: " + exonNumber);
		// System.out.println("tm: " + tm);
		// only blocksubstitution ca start before the actual transcript
		if (start < tm.getTXStart()) {

			String anno;
			if (var.equals("-"))
				anno = String.format("%s:exon%d:c.%d_%ddel", tm.getName(), exonNumber + 1, start - tm.getTXStart(),
						ref.length() + (start - tm.getTXStart()));
			else
				anno = String.format("%s:exon%d:c.%d_%ddelins%s", tm.getName(), exonNumber + 1,
						start - tm.getTXStart(), ref.length() + (start - tm.getTXStart()), var);
			Annotation ann;
			if (ref.length() == var.length())
				ann = new Annotation(tm, anno, VariantType.NON_FS_SUBSTITUTION);
			else
				ann = new Annotation(tm, anno, VariantType.FS_SUBSTITUTION);
			this.annovarFactory.addExonicAnnotation(ann);
			return;
		}

		/* frame_s indicates frame of variant, can be 0, i.e., on first base of codon, 1, or 2 */
		int frame_s = ((refvarstart - tm.getRefCDSStart()) % 3);
		int frame_end_s = ((refvarend - tm.getRefCDSStart()) % 3);
		int refcdsstart = tm.getRefCDSStart();

		// Needed to complete codon following end of multibase ref seq.
		/*
		 * The following checks for database errors where the position of the variant in the reference sequence is given
		 * as longer the actual length of the transcript.
		 */
		if (refvarstart - frame_s - 1 > tm.getActualSequenceLength()) {
			String s = String.format("%s, refvarstart=%d, frame_s=%d, seq len=%d", tm.getAccessionNumber(),
					refvarstart, frame_s, tm.getActualSequenceLength());
			Annotation ann = new Annotation(tm, s, VariantType.ERROR);
			this.annovarFactory.addErrorAnnotation(ann);
		}

		// wtnt3 represents the three nucleotides of the wildtype codon.
		String wtnt3 = tm.getWTCodonNucleotides(refvarstart, frame_s);
		if (wtnt3 == null) {
			/*
			 * This can happen is the KnownGene.txt gene definition indicates that the mRNA sequence is longer than the
			 * actual sequence contained in KnownGeneMrna.txt. This probably reflects and error in genome annotations.
			 */
			String s = String.format("Discrepancy between mRNA length and genome annotation ",
					"(variant at pos. %d of transcript with mRNA length %d):%s[%s]", refvarstart, tm.getMRNALength(),
					tm.getAccessionNumber(), tm.getName());
			Annotation ann = new Annotation(tm, s, VariantType.ERROR);
			this.annovarFactory.addErrorAnnotation(ann);
			return; /* Probably reflects a database error. */
		}
		/*
		 * wtnt3_after = Sequence of codon right after the variant. It is used for delins mutations.
		 */
		String wtnt3_after = tm.getWTCodonNucleotidesAfterVariant(refvarstart, frame_s);
		/*
		 * the following checks some database annotation errors (example: chr17:3,141,674-3,141,683), so the last coding
		 * frame is not complete and as a result, the cDNA sequence is not complete
		 */
		if (wtnt3.length() != 3 && refvarstart - frame_s - 1 >= 0) {
			String s = String.format("%s, wtnt3-length: %d", tm.getAccessionNumber(), wtnt3.length());
			Annotation ann = new Annotation(tm, s, VariantType.ERROR);
			this.annovarFactory.addErrorAnnotation(ann);
			return; /* Probably reflects some database error. */
		}
		/* If the gene is on the minus strand, we take the reverse complement of the ref and the var sequence. */
		if (tm.isMinusStrand()) {
			var = DNAUtils.reverseComplement(var);
			ref = DNAUtils.reverseComplement(ref);
		}
		// System.out.println("wtnt3=" + wtnt3);
		if (start == end) { /* SNV or insertion variant */
			if (ref.equals("-")) { /* "-" stands for an insertion at this position */
				// System.out.println(ref + "\t" + var + "\t" + tm.getChromosomeAsString() + "\t" + start);
				Annotation insrt = InsertionAnnotationBuilder.getAnnotation(tm, frame_s, wtnt3, wtnt3_after, ref, var,
						refvarstart, exonNumber);
				this.annovarFactory.addExonicAnnotation(insrt);
			} else if (var.equals("-")) { /* i.e., single nucleotide deletion */
				Annotation dlt = DeletionAnnotationBuilder.getAnnotationSingleNucleotide(tm, frame_s, wtnt3,
						wtnt3_after, ref, var, refvarstart, exonNumber);
				this.annovarFactory.addExonicAnnotation(dlt);
			} else if (var.length() > 1) {
				Annotation blck = BlockSubstitutionAnnotationBuilder.getAnnotationPlusStrand(tm, frame_s, wtnt3, ref,
						var, refvarstart, refvarend, exonNumber);
				this.annovarFactory.addExonicAnnotation(blck);
			} else {
				// System.out.println("!!!!! SNV ref=" + ref + " var=" + var);
				Annotation mssns = SingleNucleotideSubstitutionBuilder.getAnnotation(tm, frame_s, frame_end_s, wtnt3,
						ref, var, refvarstart, exonNumber);
				this.annovarFactory.addExonicAnnotation(mssns);
			}
		} else if (var.equals("-")) {
			/*
			 * If we get here, then the start position of the variant is not the same as the end position, i.e.,
			 * start==end is false, and the variant is "-"; thus there is as deletion variant involving several
			 * nucleotides.
			 */
			Annotation dltmnt = DeletionAnnotationBuilder.getMultinucleotideDeletionAnnotation(tm, frame_s, wtnt3,
					wtnt3_after, ref, var, refvarstart, refvarend, exonNumber);
			this.annovarFactory.addExonicAnnotation(dltmnt);
		} else {

			/*
			 * If we get here, then start==end is false and the variant sequence is not "-", i.e., it is not a deletion.
			 * Thus, we have a block substitution event.
			 */
			String canno = String.format("%s:exon%d:c.%d_%ddelins%s", tm.getName(), exonNumber, refvarstart
					- refcdsstart + 1, refvarend - refcdsstart + 1, var);
			// $canno = "c." . ($refvarstart-$refcdsstart+1) . "_" . ($refvarend-$refcdsstart+1) . "$obs";
			if ((refvarend - refvarstart + 1 - var.length()) % 3 == 0) {
				/* Non-frameshift substitution */
				Annotation ann = BlockSubstitutionAnnotationBuilder.getAnnotationBlockPlusStrand(tm, frame_s, wtnt3,
						ref, var, refvarstart, refvarend, exonNumber);
				this.annovarFactory.addExonicAnnotation(ann);
			} else {
				/* frameshift substitution; more than one deleted nucleotide with more than one inserted nucleotide */
				Annotation ann = BlockSubstitutionAnnotationBuilder.getAnnotationBlockPlusStrand(tm, frame_s, wtnt3,
						ref, var, refvarstart, refvarend, exonNumber);
				this.annovarFactory.addExonicAnnotation(ann);
			}
		}
	}
}
