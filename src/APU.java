public class APU {
    private final byte[] registers = new byte[0x18];

    public byte cpuRead(int address) {
        int reg = address - 0x4000;
        if (reg >= 0 && reg < registers.length) {
            if (address == 0x4015) {
                //Return Status Register (Channel enable/interrupt states)
                return registers[reg];
            }
            return registers[reg];
        }
        return 0;
    }

    public void cpuWrite(int address, byte data) {
        int reg = address - 0x4000;
        if (reg >= 0 && reg < registers.length) {
            registers[reg] = data;

            //Handle register state changes internally
            switch (address) {
                case 0x4000: //Pulse 1 Control
                case 0x4002: //Pulse 1 Timer Low
                case 0x4003: //Pulse 1 Timer High
                case 0x4015: //Channel Enable / Disable
                case 0x4017: //Frame Counter Mode
                    break;
            }
        }
    }
}