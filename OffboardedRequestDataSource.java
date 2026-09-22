package com.acme.reports;

import java.text.SimpleDateFormat;
import java.util.*;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRField;
import sailpoint.api.SailPointContext;
import sailpoint.object.*;
import sailpoint.reporting.datasource.JavaDataSource;
import sailpoint.task.Monitor;
import sailpoint.tools.GeneralException;
import sailpoint.tools.Util;

/**
 * Two cohorts, both restricted to the org levels entered on the report form:
 *
 *   Loop 1 - identities that currently have a Link to application ABC.
 *   Loop 2 - identities whose offboarding date is after the cut-off date AND
 *            who are the target of an IdentityRequest created after the
 *            cut-off date containing an item for ABC.
 *
 * An identity already reported by loop 1 is not repeated by loop 2.
 */
public class OffboardedRequestDataSource implements JavaDataSource {

    // ---------- adjust to your environment ----------
    private static final String APP_NAME        = "ABC";
    private static final String ORG_LEVEL_ATTR  = "orgLevel";          // searchable identity attribute
    private static final String OFFBOARD_ATTR   = "offboardingDate";   // searchable identity attribute
    private static final String OFFBOARD_FORMAT = "yyyy-MM-dd";        // format the attribute is stored in
    private static final List<String> DEFAULT_ORG_LEVELS = Arrays.asList("A", "B", "C");

    // report input names - must match Form fields + Signature arguments
    private static final String ARG_CUTOFF     = "cutOffDate";
    private static final String ARG_ORG_LEVELS = "orgLevels";
    // ------------------------------------------------

    private SailPointContext context;
    private Monitor monitor;
    private List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    private Iterator<Map<String, Object>> iterator;
    private Map<String, Object> current;

    public void initialize(SailPointContext context, LiveReport report,
            Attributes<String, Object> arguments, String groupBy,
            List<Sort> sort) throws GeneralException {

        this.context = context;

        // ---- Report inputs ----
        Date cutOff = toDate(arguments != null ? arguments.get(ARG_CUTOFF) : null);
        if (cutOff == null) {
            throw new GeneralException("Report input '" + ARG_CUTOFF + "' is required.");
        }
        List<String> orgLevels = toOrgLevels(arguments != null ? arguments.get(ARG_ORG_LEVELS) : null);

        // Cut-off as a string in the attribute's stored format, for the identity filter
        String cutOffStr = new SimpleDateFormat(OFFBOARD_FORMAT).format(cutOff);

        // Start of the day after the cut-off, for the request 'created' check
        Calendar cal = Calendar.getInstance();
        cal.setTime(cutOff);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_MONTH, 1);
        Date afterCutOff = cal.getTime();

        List<String> columns = Arrays.asList("id", "name", "displayName", ORG_LEVEL_ATTR, OFFBOARD_ATTR);
        Set<String> reported = new HashSet<String>();

        // ================= Loop 1: identities with an ABC link =================
        QueryOptions linkQo = new QueryOptions();
        linkQo.addFilter(Filter.and(
                Filter.in(ORG_LEVEL_ATTR, orgLevels),
                Filter.subquery("id", Link.class, "identity.id",
                        Filter.eq("application.name", APP_NAME))));
        linkQo.addOrdering("name", true);

        Iterator<Object[]> it = context.search(Identity.class, linkQo, columns);
        try {
            while (it != null && it.hasNext()) {
                Object[] r = it.next();
                addRow(r, "Has " + APP_NAME + " link");
                reported.add((String) r[0]);
            }
        } finally {
            Util.flushIterator(it);
        }

        // ====== Loop 2: offboarded after cut-off with an ABC request after cut-off ======
        QueryOptions offQo = new QueryOptions();
        offQo.addFilter(Filter.and(
                Filter.in(ORG_LEVEL_ATTR, orgLevels),
                Filter.gt(OFFBOARD_ATTR, cutOffStr)));
        offQo.addOrdering("name", true);

        it = context.search(Identity.class, offQo, columns);
        try {
            while (it != null && it.hasNext()) {
                Object[] r = it.next();
                String id = (String) r[0];

                if (reported.contains(id)) {
                    continue;   // already listed by loop 1
                }

                QueryOptions rq = new QueryOptions();
                rq.addFilter(Filter.and(
                        Filter.eq("identityRequest.targetId", id),      // parent IdentityRequest
                        Filter.eq("application", APP_NAME),             // IdentityRequestItem
                        Filter.ge("identityRequest.created", afterCutOff)));

                if (context.countObjects(IdentityRequestItem.class, rq) == 0) {
                    continue;   // no matching request -> skip
                }

                addRow(r, "Offboarded with " + APP_NAME + " request");
                reported.add(id);
            }
        } finally {
            Util.flushIterator(it);
        }

        this.iterator = rows.iterator();
    }

    private void addRow(Object[] r, String reason) {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("identityName",    r[1]);
        row.put("displayName",     r[2]);
        row.put("orgLevel",        r[3]);
        row.put("offboardingDate", r[4]);
        row.put("reason",          reason);
        rows.add(row);
    }

    /** Comma-separated string (or a List) -> trimmed values; defaults to A, B, C. */
    private List<String> toOrgLevels(Object v) {
        List<String> out = new ArrayList<String>();
        if (v instanceof Collection) {
            for (Object o : (Collection) v) {
                if (o != null && o.toString().trim().length() > 0) out.add(o.toString().trim());
            }
        } else if (v != null) {
            for (String s : v.toString().split(",")) {
                if (s.trim().length() > 0) out.add(s.trim());
            }
        }
        return out.isEmpty() ? DEFAULT_ORG_LEVELS : out;
    }

    /** Form date values can arrive as Date, epoch millis, or a string. */
    private Date toDate(Object v) {
        if (v == null) return null;
        if (v instanceof Date) return (Date) v;
        if (v instanceof Number) return new Date(((Number) v).longValue());
        String s = v.toString().trim();
        if (s.length() == 0) return null;
        try {
            return new Date(Long.parseLong(s));
        } catch (NumberFormatException ignore) { }
        String[] patterns = { "MM/dd/yyyy", "yyyy-MM-dd" };
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p);
                f.setLenient(false);
                return f.parse(s);
            } catch (Exception ignore) { }
        }
        return null;
    }

    public boolean next() throws JRException {
        if (iterator != null && iterator.hasNext()) {
            current = iterator.next();
            return true;
        }
        return false;
    }

    public Object getFieldValue(JRField field) throws JRException {
        return current != null ? current.get(field.getName()) : null;
    }

    public Object getFieldValue(String field) throws GeneralException {
        return current != null ? current.get(field) : null;
    }

    public int getSizeEstimate() throws GeneralException {
        return rows.size();
    }

    public void setLimit(int startRow, int pageSize) {
        // all rows are prebuilt in initialize()
    }

    public void setMonitor(Monitor monitor) {
        this.monitor = monitor;
    }

    public void close() {
    }

    public String getBaseHql() {
        return null;
    }

    public QueryOptions getBaseQueryOptions() {
        return null;
    }
}
