package co.issec.reports;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRField;

import sailpoint.api.SailPointContext;
import sailpoint.object.Attributes;
import sailpoint.object.Bundle;
import sailpoint.object.Filter;
import sailpoint.object.LiveReport;
import sailpoint.object.Policy;
import sailpoint.object.QueryOptions;
import sailpoint.object.SODConstraint;
import sailpoint.object.Sort;
import sailpoint.reporting.datasource.JavaDataSource;
import sailpoint.task.Monitor;
import sailpoint.tools.GeneralException;
import sailpoint.tools.Util;

/**
 * Live report data source that flattens SoD policies into ONE ROW PER
 * SOD CONSTRAINT (rule). Each row exposes the policy name, the policy type,
 * the constraint name, and the left / right conflicting role (Bundle) lists.
 *
 * Multi-valued left/right role lists are pre-joined with '\n' so that, on
 * CSV export, each role lands on its own line inside a single quoted cell
 * (the CSV writer quotes any field that contains a newline).
 *
 * Deploy:
 *   1. Compile against identityiq.jar (same JDK as your IIQ build).
 *   2. Drop the .class under WEB-INF/classes/com/acme/reports/ (or jar it
 *      into WEB-INF/lib). Restart IIQ so the class is picked up.
 *   3. Import SoDConstraintDetailReport.xml (Debug > Import, or iiq console).
 *
 * VERIFY against your version's javadoc before compiling (the two things
 * most likely to differ):
 *   - Policy.getSODConstraints()  -> List<SODConstraint>
 *   - SODConstraint.getLeftBundles() / getRightBundles() -> List<Bundle>
 * For advanced / query-based SoD policies the rules are GenericConstraint,
 * not SODConstraint, and carry no left/right bundles - handle separately.
 */
public class SodConstraintDataSource implements JavaDataSource {

    private SailPointContext context;
    private Monitor monitor;

    // Full result set built up front (policy/constraint volumes are small).
    private List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    private Iterator<Map<String, Object>> iterator;
    private Map<String, Object> current;

    // Separator placed between role names within a single cell; set from the
    // "bundleSeparator" report input in initialize(). Defaults to newline.
    private String separator = "\n";

    // Optional paging bounds (retained but unused: everything is prebuilt).
    private int startRow = 0;
    private int pageSize = 0;

    public void initialize(SailPointContext context, LiveReport report,
            Attributes<String, Object> arguments, String groupBy,
            List<Sort> sort) throws GeneralException {

        this.context = context;

        // Optional exact-match policy filter, if you later wire up a form.
        String policyNameFilter =
                (arguments != null) ? arguments.getString("policyName") : null;

        // Role separator chosen in the report UI ("Comma" or "New line").
        // Anything other than "Comma" -> newline (the safe default).
        String sepArg =
                (arguments != null) ? arguments.getString("bundleSeparator") : null;
        this.separator = "Comma".equalsIgnoreCase(sepArg) ? ", " : "\n";

        QueryOptions qo = new QueryOptions();
        if (Util.isNotNullOrEmpty(policyNameFilter)) {
            qo.addFilter(Filter.eq("name", policyNameFilter));
        }
        qo.addOrdering("name", true);

        // Iterate ids, load one policy at a time, decache as we go so the
        // Hibernate session stays small even with many policies.
        Iterator<Object[]> ids =
                context.search(Policy.class, qo, Arrays.asList("id"));

        while (ids != null && ids.hasNext()) {
            String policyId = (String) ids.next()[0];
            Policy policy = context.getObjectById(Policy.class, policyId);
            if (policy == null) {
                continue;
            }
            try {
                List sodConstraints = policy.getSODConstraints();   // see VERIFY note
                if (sodConstraints == null) {
                    continue;   // not an SoD policy (or no role rules) -> skip
                }
                for (Object co : sodConstraints) {
                    SODConstraint c = (SODConstraint) co;

                    Map<String, Object> row = new HashMap<String, Object>();
                    row.put("policyName", policy.getName());
                    row.put("policyType", policy.getType());
                    row.put("constraintName",
                            Util.isNotNullOrEmpty(c.getName())
                                    ? c.getName() : "(unnamed rule)");
                    row.put("leftBundles",  joinBundleNames(c.getLeftBundles()));
                    row.put("rightBundles", joinBundleNames(c.getRightBundles()));
                    rows.add(row);
                }
            } finally {
                context.decache(policy);
            }
        }

        this.iterator = rows.iterator();
    }

    /**
     * Join role names with the configured separator. With a newline the CSV
     * writer wraps the field in quotes and each role shows on its own line
     * within one cell; with a comma they render inline (still quoted, since
     * the field then contains the delimiter).
     */
    private String joinBundleNames(List bundles) {
        if (bundles == null || bundles.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object o : bundles) {
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(((Bundle) o).getName());
        }
        return sb.toString();
    }

    public boolean next() throws JRException {
        if (iterator != null && iterator.hasNext()) {
            current = iterator.next();
            return true;
        }
        current = null;
        return false;
    }

    public Object getFieldValue(JRField field) throws JRException {
        try {
            return getFieldValue(field.getName());
        } catch (GeneralException ge) {
            throw new JRException(ge);
        }
    }

    public Object getFieldValue(String field) throws GeneralException {
        return (current != null) ? current.get(field) : null;
    }

    public int getSizeEstimate() throws GeneralException {
        return rows.size();
    }

    /**
     * Declared by LiveReportDataSource (which JavaDataSource extends) in this
     * IIQ version. Query-backed datasources return their underlying query here
     * so the engine can run it directly; this datasource builds rows in memory,
     * so there is no reusable base query -> return null.
     */
    public String getBaseHql() {
        return null;
    }

    public QueryOptions getBaseQueryOptions() {
        return null;
    }

    public void setLimit(int startRow, int pageSize) {
        this.startRow = startRow;
        this.pageSize = pageSize;
    }

    public void setMonitor(Monitor monitor) {
        this.monitor = monitor;
    }

    public void close() {
        // Nothing to release; result set is held in memory.
    }
}
