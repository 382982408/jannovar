package de.charite.compbio.jannovar.hgvs.bridge;

import htsjdk.samtools.reference.IndexedFastaSequenceFile;

import java.io.File;
import java.io.FileNotFoundException;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.io.Files;

import de.charite.compbio.jannovar.data.JannovarData;
import de.charite.compbio.jannovar.data.JannovarDataSerializer;
import de.charite.compbio.jannovar.data.SerializationException;
import de.charite.compbio.jannovar.hgvs.HGVSVariant;
import de.charite.compbio.jannovar.hgvs.nts.variant.SingleAlleleNucleotideVariant;
import de.charite.compbio.jannovar.hgvs.parser.HGVSParserDriver;
import de.charite.compbio.jannovar.reference.GenomeVariant;
import de.charite.compbio.jannovar.utils.ResourceUtils;

/**
 * Tests using FBN1 transcript that is on the reverse strand.
 *
 * @author Manuel Holtgrewe <manuel.holtgrewe@bihealth.de>
 */
public class NucleotideChangeToGenomeVariantReverseStrandTest {

	/** path to Jannovar database file */
	static String dbPath;
	/** path to Jannovar database file */
	static String fastaPath;
	/** Jannovar database */
	JannovarData jannovarData;
	/** Translation of NucleotideChange to GenomeVariant */
	NucleotideChangeToGenomeVariantTranslator translator;

	@BeforeClass
	public static void setUpClass() throws Exception {
		// copy out files to temporary directory
		File tmpDir = Files.createTempDir();
		dbPath = tmpDir + "/mini_fbn1.ser";
		ResourceUtils.copyResourceToFile("/ex_fbn1/mini_fbn1.ser", new File(dbPath));
		fastaPath = tmpDir + "/ref.fa";
		ResourceUtils.copyResourceToFile("/ex_fbn1/ref.fa", new File(fastaPath));
		String faiPath = tmpDir + "/ref.fa.fai";
		ResourceUtils.copyResourceToFile("/ex_fbn1/ref.fa.fai", new File(faiPath));
	}

	@Before
	public void setUp() throws FileNotFoundException, SerializationException {
		jannovarData = new JannovarDataSerializer(dbPath).load();

		IndexedFastaSequenceFile fastaFile = new IndexedFastaSequenceFile(new File(fastaPath));
		translator = new NucleotideChangeToGenomeVariantTranslator(jannovarData, fastaFile);
	}

	@Test
	public void testPositionInCDS() throws CannotTranslateHGVSVariant {
		String hgvsStr = "NM_000138.4(FBN1):c.7339G>A";
		HGVSVariant hgvsVar = new HGVSParserDriver().parseHGVSString(hgvsStr);
		Assert.assertEquals(hgvsStr, hgvsVar.toHGVSString());

		SingleAlleleNucleotideVariant saVar = (SingleAlleleNucleotideVariant) hgvsVar;
		GenomeVariant gVar = translator.translateNucleotideVariantToGenomeVariant(saVar);
		Assert.assertEquals("ref:g.217680C>T", gVar.toString());
	}

	@Test
	public void testPositionWithPositiveOffsetInCDS() throws CannotTranslateHGVSVariant {
		String hgvsStr = "NM_000138.4(FBN1):c.7339+1G>A";
		HGVSVariant hgvsVar = new HGVSParserDriver().parseHGVSString(hgvsStr);
		Assert.assertEquals(hgvsStr, hgvsVar.toHGVSString());

		SingleAlleleNucleotideVariant saVar = (SingleAlleleNucleotideVariant) hgvsVar;
		GenomeVariant gVar = translator.translateNucleotideVariantToGenomeVariant(saVar);
		Assert.assertEquals("ref:g.217679C>T", gVar.toString());
	}

	@Test
	public void testPositionWithNegativeOffsetInCDS() throws CannotTranslateHGVSVariant {
		String hgvsStr = "NM_000138.4(FBN1):c.7339-1G>A";
		HGVSVariant hgvsVar = new HGVSParserDriver().parseHGVSString(hgvsStr);
		Assert.assertEquals(hgvsStr, hgvsVar.toHGVSString());

		SingleAlleleNucleotideVariant saVar = (SingleAlleleNucleotideVariant) hgvsVar;
		GenomeVariant gVar = translator.translateNucleotideVariantToGenomeVariant(saVar);
		Assert.assertEquals("ref:g.217681C>T", gVar.toString());
	}

	@Test
	public void testPositionInUTR5() throws CannotTranslateHGVSVariant {
		String hgvsStr = "NM_000138.4(FBN1):c.-1G>A";
		HGVSVariant hgvsVar = new HGVSParserDriver().parseHGVSString(hgvsStr);
		Assert.assertEquals(hgvsStr, hgvsVar.toHGVSString());

		SingleAlleleNucleotideVariant saVar = (SingleAlleleNucleotideVariant) hgvsVar;
		GenomeVariant gVar = translator.translateNucleotideVariantToGenomeVariant(saVar);
		Assert.assertEquals("ref:g.436967C>T", gVar.toString());
	}

	@Test
	public void testPositionWithPositiveOffsetInUTR5() throws CannotTranslateHGVSVariant {
		String hgvsStr = "NM_000138.4(FBN1):c.-1+1G>A";
		HGVSVariant hgvsVar = new HGVSParserDriver().parseHGVSString(hgvsStr);
		Assert.assertEquals(hgvsStr, hgvsVar.toHGVSString());

		SingleAlleleNucleotideVariant saVar = (SingleAlleleNucleotideVariant) hgvsVar;
		GenomeVariant gVar = translator.translateNucleotideVariantToGenomeVariant(saVar);
		Assert.assertEquals("ref:g.436966C>T", gVar.toString());
	}

	@Test
	public void testPositionWithNegativeOffsetInUTR5() throws CannotTranslateHGVSVariant {
		String hgvsStr = "NM_000138.4(FBN1):c.-1-1G>A";
		HGVSVariant hgvsVar = new HGVSParserDriver().parseHGVSString(hgvsStr);
		Assert.assertEquals(hgvsStr, hgvsVar.toHGVSString());

		SingleAlleleNucleotideVariant saVar = (SingleAlleleNucleotideVariant) hgvsVar;
		GenomeVariant gVar = translator.translateNucleotideVariantToGenomeVariant(saVar);
		Assert.assertEquals("ref:g.436968C>T", gVar.toString());
	}

	@Test
	public void testPositionInUTR3() throws CannotTranslateHGVSVariant {
		String hgvsStr = "NM_000138.4(FBN1):c.*1G>A";
		HGVSVariant hgvsVar = new HGVSParserDriver().parseHGVSString(hgvsStr);
		Assert.assertEquals(hgvsStr, hgvsVar.toHGVSString());

		SingleAlleleNucleotideVariant saVar = (SingleAlleleNucleotideVariant) hgvsVar;
		GenomeVariant gVar = translator.translateNucleotideVariantToGenomeVariant(saVar);
		Assert.assertEquals("ref:g.203186C>T", gVar.toString());
	}

	@Test
	public void testPositionWithPositiveOffsetInUTR3() throws CannotTranslateHGVSVariant {
		String hgvsStr = "NM_000138.4(FBN1):c.*1+1G>A";
		HGVSVariant hgvsVar = new HGVSParserDriver().parseHGVSString(hgvsStr);
		Assert.assertEquals(hgvsStr, hgvsVar.toHGVSString());

		SingleAlleleNucleotideVariant saVar = (SingleAlleleNucleotideVariant) hgvsVar;
		GenomeVariant gVar = translator.translateNucleotideVariantToGenomeVariant(saVar);
		Assert.assertEquals("ref:g.203185C>T", gVar.toString());
	}

	@Test
	public void testPositionWithNegativeOffsetInUTR3() throws CannotTranslateHGVSVariant {
		String hgvsStr = "NM_000138.4(FBN1):c.*1-1G>A";
		HGVSVariant hgvsVar = new HGVSParserDriver().parseHGVSString(hgvsStr);
		Assert.assertEquals(hgvsStr, hgvsVar.toHGVSString());

		SingleAlleleNucleotideVariant saVar = (SingleAlleleNucleotideVariant) hgvsVar;
		GenomeVariant gVar = translator.translateNucleotideVariantToGenomeVariant(saVar);
		Assert.assertEquals("ref:g.203187C>T", gVar.toString());
	}

}