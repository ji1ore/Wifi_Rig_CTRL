Import("env")
import os

# 出力先
output_dir = os.path.join(env.subst("$BUILD_DIR"))
output_bin = os.path.join(output_dir, "merged-firmware.bin")

# 入力ファイル
bootloader = os.path.join(output_dir, "bootloader.bin")
partitions = os.path.join(output_dir, "partitions.bin")
app = os.path.join(output_dir, "firmware.bin")

# 結合コマンド
env.AddPostAction(
    "buildprog",
    env.VerboseAction(
        '$PYTHONEXE -m esptool --chip esp32 merge_bin -o "{}" --flash_mode dio --flash_freq 40m --flash_size 4MB '
        '0x1000 "{}" 0x8000 "{}" 0x10000 "{}"'.format(output_bin, bootloader, partitions, app),
        "Merging firmware into merged-firmware.bin"
    )
)
