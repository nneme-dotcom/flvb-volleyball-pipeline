package lu.volley.etl;

/**
 * Thrown when a source record cannot be loaded. The reason code is stored in
 * {@code etl_reject} so data-quality problems can be counted and queried.
 */
public class RejectException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String reasonCode;

    public RejectException(String reasonCode, String detail) {
        super(detail);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
