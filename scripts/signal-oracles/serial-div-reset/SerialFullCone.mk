# SPDX-License-Identifier: MIT

ifndef ORACLE_DMG
$(error ORACLE_DMG must point to the pinned dmg-sim checkout)
endif
ifndef SERIAL_ORACLE_TB
$(error SERIAL_ORACLE_TB must point to serial_full_cone_tb.sv)
endif
ifndef SERIAL_ORACLE_OUT
$(error SERIAL_ORACLE_OUT must name the temporary VVP output)
endif

include $(ORACLE_DMG)/Makefile

override TIMING = default

$(SERIAL_ORACLE_OUT): $(SERIAL_ORACLE_TB) $(DMG_CPU_B) $(SM83) $(COMMON_FILES) $(TIMESCALE)
	$(IVERILOG) $(IVERILOG_FLAGS) -DSIMPLIFIED_OAM -DSIMPLIFIED_WAVERAM \
		-s serial_full_cone_tb -o $@ \
		$(COMMON_FILES) $(SM83) $(DMG_CPU_B) $(SERIAL_ORACLE_TB)
