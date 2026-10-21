#!/usr/bin/env python3
from pathlib import Path
import sys
root=Path(sys.argv[1]);llama=(root/"include/llama.h").read_text();backend=(root/"ggml/include/ggml-backend.h").read_text()
required={"llama.h":(llama,["llama_model_load_from_file","load_mode","LLAMA_LOAD_MODE_MMAP","n_gpu_layers","devices","offload_kqv","op_offload","LLAMA_FLASH_ATTN_TYPE_AUTO","llama_memory_seq_rm"]),
"ggml-backend.h":(backend,["ggml_backend_reg_count","ggml_backend_reg_get","ggml_backend_reg_dev_count","ggml_backend_reg_dev_get","ggml_backend_dev_description"])}
missing=[file+": "+symbol for file,(text,symbols) in required.items() for symbol in symbols if symbol not in text]
if missing:raise SystemExit("Native API mismatch: "+", ".join(missing))
