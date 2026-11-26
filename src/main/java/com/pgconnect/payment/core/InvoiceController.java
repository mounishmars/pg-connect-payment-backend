package com.pgconnect.payment.core;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name="bearerAuth")
public class InvoiceController {
    private final JdbcTemplate jdbc;
    private final Access access;
    public InvoiceController(JdbcTemplate jdbc,Access access) { this.jdbc=jdbc; this.access=access; }
    public record InvoiceInput(@NotNull UUID bookingId,
            @NotNull @DecimalMin("0.01") @Digits(integer=12,fraction=2) BigDecimal amount,@NotNull LocalDate dueDate) {}
    @PostMapping("/owner/invoices")
    public Map<String,Object> create(@AuthenticationPrincipal UUID user,@Valid @RequestBody InvoiceInput body) {
        String number="INV-"+UUID.randomUUID().toString().toUpperCase();
        List<Map<String,Object>> rows=jdbc.queryForList("""
                INSERT INTO rent_invoices(invoice_number,tenant_user_id,owner_id,property_id,total_amount,due_date)
                SELECT ?,bk.tenant_user_id,p.owner_id,p.id,?,?
                FROM bookings bk JOIN beds b ON b.id=bk.bed_id JOIN rooms r ON r.id=b.room_id
                JOIN properties p ON p.id=r.property_id WHERE bk.id=? AND bk.status='APPROVED' AND p.owner_id=?
                RETURNING id,invoice_number AS "invoiceNumber",status
                """,number,body.amount(),body.dueDate(),body.bookingId(),access.owner(user));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Active booking not found");
        return rows.get(0);
    }
    @GetMapping("/customer/invoices")
    public List<Map<String,Object>> customer(@AuthenticationPrincipal UUID user) { return list("tenant_user_id",user); }
    @GetMapping("/owner/invoices")
    public List<Map<String,Object>> owner(@AuthenticationPrincipal UUID user) { return list("owner_id",access.owner(user)); }
    private List<Map<String,Object>> list(String column,UUID id) {
        return jdbc.queryForList("""
                SELECT id,invoice_number AS "invoiceNumber",tenant_user_id AS "tenantId",property_id AS "propertyId",
                  currency,total_amount AS "totalAmount",amount_paid AS "amountPaid",total_amount-amount_paid AS balance,
                  due_date AS "dueDate",status
                FROM rent_invoices WHERE
                """+column+"=? ORDER BY created_at DESC LIMIT 100",id);
    }
    @GetMapping("/owner/dashboard")
    public Map<String,Object> dashboard(@AuthenticationPrincipal UUID user) {
        UUID owner=access.owner(user);
        return jdbc.queryForMap("""
                SELECT
                  (SELECT count(*) FROM beds b JOIN rooms r ON r.id=b.room_id JOIN properties p ON p.id=r.property_id
                    WHERE p.owner_id=? AND ((p.active AND r.active AND b.active) OR EXISTS
                      (SELECT 1 FROM bookings bk WHERE bk.bed_id=b.id AND bk.status='APPROVED'))) AS "totalBeds",
                  (SELECT count(*) FROM bookings bk JOIN beds b ON b.id=bk.bed_id JOIN rooms r ON r.id=b.room_id
                    JOIN properties p ON p.id=r.property_id WHERE p.owner_id=? AND bk.status='APPROVED') AS occupied,
                  (SELECT count(*) FROM beds b JOIN rooms r ON r.id=b.room_id JOIN properties p ON p.id=r.property_id
                    WHERE p.owner_id=? AND p.active AND r.active AND b.active AND NOT EXISTS
                      (SELECT 1 FROM bookings bk WHERE bk.bed_id=b.id AND bk.status='APPROVED')) AS "vacantBeds",
                  (SELECT COALESCE(sum(total_amount-amount_paid),0) FROM rent_invoices
                    WHERE owner_id=? AND status IN ('UNPAID','PARTIALLY_PAID')) AS "rentDue"
                """,owner,owner,owner,owner);
    }
}
