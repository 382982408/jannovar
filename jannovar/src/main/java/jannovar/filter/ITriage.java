package exomizer.filter;



/**
 * This interface is implemented by classes that perform filtering of
 * the variants in the VCF file according to various criteria. An ITriage object
 * gets attached to each Variant object. The function passesFIlter can be used to 
 * find out the results of the filter and the variant can be deleted it it did not
 * pass the filter.
 * @author Peter N Robinson
 * @version 0.01 (August 22,2012)
 */
public interface ITriage {
    /** @return true if the variant being analyzed passes the filter (e.g., is rare, pathogenic, or has high quality reads) */
    public boolean passesFilter();
    /** @return return a float representation of the filter result [0..1]. 
     * If the result is boolean, return 0.0 for false and 1.0 for true */
    public float filterResult();
    /** @return A string with a summary of the filtering results .*/
    public String getFilterResultSummary();
    /** @return A list with detailed results of filtering. The list is intended to be displayed as an HTML list if desired. */
    public java.util.ArrayList<String> getFilterResultList();
    /** @return HTML code for a cell representing the current triage result. */
    public String getHTMLCode();

}