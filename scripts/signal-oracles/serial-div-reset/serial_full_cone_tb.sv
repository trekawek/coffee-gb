// SPDX-License-Identifier: MIT
`timescale 1ns/1ps

// Independently written driver for the pinned external dmg-sim hierarchy. It forces only the
// upstream controls needed to distinguish the three DIV-reset/SCK cases. No external source or
// generated netlist is copied into Coffee GB.
module serial_full_cone_tb;
    logic cpu_wr_drive, unyk_n_drive, uwam_drive, reset2_n_drive, eder_drive;
    integer shift_edges;
    dmg_cpu_b dut();

    always @(posedge dut.serial_tick_n) shift_edges = shift_edges + 1;

    task automatic settle;
        #1000;
    endtask

    task automatic raise_stage;
        begin
            unyk_n_drive = 0;
            settle();
            unyk_n_drive = 1;
            settle();
            unyk_n_drive = 0;
            settle();
            if (dut.tama !== 1) $fatal(1, "raise failed tama=%b", dut.tama);
        end
    endtask

    task automatic div_write;
        begin
            cpu_wr_drive = 1;
            settle();
            if (dut.tape !== 1 || dut.reset_div_n !== 0)
                $fatal(1, "decode failed tape=%b reset_div_n=%b", dut.tape, dut.reset_div_n);
            cpu_wr_drive = 0;
            settle();
        end
    endtask

    initial begin
        cpu_wr_drive = 0;
        unyk_n_drive = 0;
        uwam_drive = 0;
        reset2_n_drive = 0;
        eder_drive = 1;
        shift_edges = 0;
        force dut.ff04_ff07 = 1'b1;
        force dut.tola = 1'b1;
        force dut.tovy = 1'b1;
        force dut.cpu_wr = cpu_wr_drive;
        force dut.ucob = 1'b0;
        force dut.reset = 1'b0;
        force dut.unyk_n = unyk_n_drive;
        force dut.sck_dir = 1'b1;
        force dut.sck_en_n = 1'b0;
        force dut.uwam = uwam_drive;
        force dut.reset2_n = reset2_n_drive;
        force dut.eder = eder_drive;
        settle();
        uwam_drive = 1;
        reset2_n_drive = 1;
        settle();
        shift_edges = 0;

        raise_stage();
        if ({dut.tama,dut.coty,dut.dawa,dut.serial_tick_n,dut.ser_out} !== 5'b10100)
            $fatal(1, "A before mismatch");
        div_write();
        if ({dut.tama,dut.coty,dut.dawa,dut.serial_tick_n,dut.ser_out} !== 5'b01011
                || shift_edges !== 1) $fatal(1, "A after mismatch");
        $display("FULL_CASE_A stage_high sck_high -> toggle_to_sck_low shift=1");

        raise_stage();
        eder_drive = 0;
        settle();
        if ({dut.tama,dut.coty,dut.dawa,dut.serial_tick_n,dut.ser_out} !== 5'b11011)
            $fatal(1, "B before mismatch");
        div_write();
        if ({dut.tama,dut.coty,dut.dawa,dut.serial_tick_n,dut.ser_out} !== 5'b00101
                || shift_edges !== 1) $fatal(1, "B after mismatch");
        $display("FULL_CASE_B stage_high sck_low -> toggle_to_sck_high shift=0");

        div_write();
        if ({dut.tama,dut.coty,dut.dawa,dut.serial_tick_n,dut.ser_out} !== 5'b00101
                || shift_edges !== 1) $fatal(1, "C mismatch");
        $display("FULL_CASE_C stage_low -> no_toggle shift=0");
        $display("FULL_PASS exact dmg_cpu_b hierarchy");
        $finish;
    end
endmodule
