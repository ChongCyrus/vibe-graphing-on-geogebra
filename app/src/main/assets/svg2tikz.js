/*
 * SvgToTikzConverter - extracted from the obsidian-svg2tikz project
 * (D:\DSH\WD\ggbForAndroid\obsidian-svg2tikz) and from tikz-By-ggb-for-Obsidian.
 * Pure browser version: exposes window.SvgToTikzConverter.
 */

class SvgToTikzConverter {
	constructor(settings) {
		this.settings = settings;
		this.colors = [];
		this.colorCode = '';
		this.indent = settings.indent ? '  ' : '';
		this.height = 0;
		this.width = 0;
	}

	convert(svg) {
		const vb = svg.getAttribute('viewBox');
		if (vb) {
			const parts = vb.split(/\s+/).map(parseFloat);
			this.width = parts[2];
			this.height = parts[3];
		} else {
			this.width = parseFloat(svg.getAttribute('width')) || 100;
			this.height = parseFloat(svg.getAttribute('height')) || 100;
		}

		let code = this.processNode(svg, 0);

		const mode = this.settings.outputMode;
		const unit = this.settings.outputUnit;
		const scale = this.settings.scale;
		const ysign = ''; // Fixed: removed '-' to avoid double Y-flip with coord()

		if (mode === 'standalone') {
			return `\\documentclass{article}
\\usepackage[utf8]{inputenc}
\\usepackage{tikz}
${this.colorCode}\\def\\globalscale {${scale}}
\\begin{document}
\\begin{tikzpicture}[y=1${unit}, x=1${unit}, yscale=${ysign}\\globalscale, xscale=\\globalscale, every node/.append style={scale=\\globalscale}, inner sep=0pt, outer sep=0pt]
${code}\\end{tikzpicture}
\\end{document}`;
		} else if (mode === 'figonly') {
			return `${this.colorCode}\\def\\globalscale {${scale}}
\\begin{tikzpicture}[y=1${unit}, x=1${unit}, yscale=${ysign}\\globalscale, xscale=\\globalscale, every node/.append style={scale=\\globalscale}, inner sep=0pt, outer sep=0pt]
${code}\\end{tikzpicture}`;
		} else {
			return code;
		}
	}

	processNode(node, depth) {
		if (node.nodeType !== 1) return '';
		const tag = node.tagName.toLowerCase();

		if (['defs', 'title', 'desc', 'metadata', 'style', 'script'].includes(tag)) {
			return '';
		}

		const indent = this.indent.repeat(depth);
		let result = '';

		if (tag === 'g') {
			result += this.processGroup(node, depth);
		} else if (tag === 'path') {
			result += this.processPath(node, indent);
		} else if (tag === 'rect') {
			result += this.processRect(node, indent);
		} else if (tag === 'circle') {
			result += this.processCircle(node, indent);
		} else if (tag === 'ellipse') {
			result += this.processEllipse(node, indent);
		} else if (tag === 'line') {
			result += this.processLine(node, indent);
		} else if (tag === 'polyline' || tag === 'polygon') {
			result += this.processPoly(node, indent, tag === 'polygon');
		} else if (tag === 'text') {
			result += this.processText(node, indent);
		} else if (tag === 'svg' || tag === 'switch') {
			for (const child of node.children) {
				result += this.processNode(child, depth);
			}
		}

		return result;
	}

	processGroup(node, depth) {
		const options = this.getStyleOptions(node) + this.getTransformOptions(node);
		let code = '';
		for (const child of node.children) {
			code += this.processNode(child, depth + 1);
		}
		if (!code) return '';

		const indent = this.indent.repeat(depth);
		if (options) {
			return `${indent}\\begin{scope}[${options}]\n${code}${indent}\\end{scope}\n`;
		}
		return code;
	}

	processPath(node, indent) {
		const d = node.getAttribute('d');
		if (!d) return '';

		const options = this.getStyleOptions(node) + this.getTransformOptions(node);
		const pathCode = this.convertPathData(d);
		return `${indent}\\path[${options}] ${pathCode};\n`;
	}

	processRect(node, indent) {
		const x = parseFloat(node.getAttribute('x')) || 0;
		const y = parseFloat(node.getAttribute('y')) || 0;
		const w = parseFloat(node.getAttribute('width')) || 0;
		const h = parseFloat(node.getAttribute('height')) || 0;
		const rx = parseFloat(node.getAttribute('rx')) || 0;
		const ry = parseFloat(node.getAttribute('ry')) || 0;

		const p1 = this.coord(x, y);
		const p2 = this.coord(x + w, y + h);

		let options = this.getStyleOptions(node) + this.getTransformOptions(node);
		if (rx > 0 || ry > 0) {
			const r = Math.max(rx, ry);
			options += (options ? ',' : '') + `rounded corners=${this.round(this.toUnit(r))}${this.settings.outputUnit}`;
		}

		return `${indent}\\path[${options}] ${p1} rectangle ${p2};\n`;
	}

	processCircle(node, indent) {
		const cx = parseFloat(node.getAttribute('cx')) || 0;
		const cy = parseFloat(node.getAttribute('cy')) || 0;
		const r = parseFloat(node.getAttribute('r')) || 0;

		const options = this.getStyleOptions(node) + this.getTransformOptions(node);
		const center = this.coord(cx, cy);
		const radius = this.round(this.toUnit(r));

		return `${indent}\\path[${options}] ${center} circle (${radius}${this.settings.outputUnit});\n`;
	}

	processEllipse(node, indent) {
		const cx = parseFloat(node.getAttribute('cx')) || 0;
		const cy = parseFloat(node.getAttribute('cy')) || 0;
		const rx = parseFloat(node.getAttribute('rx')) || 0;
		const ry = parseFloat(node.getAttribute('ry')) || 0;

		const options = this.getStyleOptions(node) + this.getTransformOptions(node);
		const center = this.coord(cx, cy);
		const rxu = this.round(this.toUnit(rx));
		const ryu = this.round(this.toUnit(ry));

		return `${indent}\\path[${options}] ${center} ellipse (${rxu}${this.settings.outputUnit} and ${ryu}${this.settings.outputUnit});\n`;
	}

	processLine(node, indent) {
		const x1 = parseFloat(node.getAttribute('x1')) || 0;
		const y1 = parseFloat(node.getAttribute('y1')) || 0;
		const x2 = parseFloat(node.getAttribute('x2')) || 0;
		const y2 = parseFloat(node.getAttribute('y2')) || 0;

		const options = this.getStyleOptions(node) + this.getTransformOptions(node);
		const p1 = this.coord(x1, y1);
		const p2 = this.coord(x2, y2);

		return `${indent}\\path[${options}] ${p1} -- ${p2};\n`;
	}

	processPoly(node, indent, close) {
		const points = node.getAttribute('points');
		if (!points) return '';

		const coords = points.trim().split(/[\s,]+/).filter(s => s).map(parseFloat);
		let path = '';
		for (let i = 0; i < coords.length; i += 2) {
			if (i > 0) path += ' -- ';
			path += this.coord(coords[i], coords[i + 1]);
		}
		if (close) path += ' -- cycle';

		const options = this.getStyleOptions(node) + this.getTransformOptions(node);
		return `${indent}\\path[${options}] ${path};\n`;
	}

	processText(node, indent) {
		if (this.settings.ignoreText) return '';

		const x = parseFloat(node.getAttribute('x')) || 0;
		const y = parseFloat(node.getAttribute('y')) || 0;
		const text = node.textContent || '';
		const escaped = this.escapeTex(text);

		let options = this.getStyleOptions(node) + this.getTransformOptions(node, true);
		if (!options.includes('anchor=')) {
			options += (options ? ',' : '') + 'anchor=south west';
		}

		const pos = this.coord(x, y);
		return `${indent}\\node[${options}] at ${pos} {${escaped}};\n`;
	}

	// ==================== Path Data Parser ====================

	convertPathData(d) {
		const tokens = d.match(/[MmLlHhVvCcSsQqTtAaZz][^MmLlHhVvCcSsQqTtAaZz]*/g) || [];
		let result = '';
		let current = { x: 0, y: 0 };
		let start = { x: 0, y: 0 };
		let lastCubicCp = null;
		let lastQuadCp = null;

		for (const token of tokens) {
			const cmd = token[0];
			const nums = token.slice(1).trim().split(/[\s,]+/).filter(s => s).map(parseFloat);
			let i = 0;
			const abs = cmd === cmd.toUpperCase();

			switch (cmd.toUpperCase()) {
				case 'M':
					while (i < nums.length) {
						const x = abs ? nums[i] : current.x + nums[i];
						const y = abs ? nums[i + 1] : current.y + nums[i + 1];
						if (i === 0) {
							result += this.coord(x, y);
							start = { x, y };
						} else {
							result += ` -- ${this.coord(x, y)}`;
						}
						current = { x, y };
						i += 2;
					}
					lastCubicCp = null;
					lastQuadCp = null;
					break;

				case 'L':
					while (i < nums.length) {
						const x = abs ? nums[i] : current.x + nums[i];
						const y = abs ? nums[i + 1] : current.y + nums[i + 1];
						result += ` -- ${this.coord(x, y)}`;
						current = { x, y };
						i += 2;
					}
					break;

				case 'H':
					while (i < nums.length) {
						const x = abs ? nums[i] : current.x + nums[i];
						result += ` -- ${this.coord(x, current.y)}`;
						current.x = x;
						i++;
					}
					break;

				case 'V':
					while (i < nums.length) {
						const y = abs ? nums[i] : current.y + nums[i];
						result += ` -- ${this.coord(current.x, y)}`;
						current.y = y;
						i++;
					}
					break;

				case 'C':
					while (i < nums.length) {
						const x1 = abs ? nums[i] : current.x + nums[i];
						const y1 = abs ? nums[i + 1] : current.y + nums[i + 1];
						const x2 = abs ? nums[i + 2] : current.x + nums[i + 2];
						const y2 = abs ? nums[i + 3] : current.y + nums[i + 3];
						const x = abs ? nums[i + 4] : current.x + nums[i + 4];
						const y = abs ? nums[i + 5] : current.y + nums[i + 5];
						result += ` .. controls ${this.coord(x1, y1)} and ${this.coord(x2, y2)} .. ${this.coord(x, y)}`;
						lastCubicCp = { x: x2, y: y2 };
						current = { x, y };
						i += 6;
					}
					break;

				case 'S':
					while (i < nums.length) {
						const x2 = abs ? nums[i] : current.x + nums[i];
						const y2 = abs ? nums[i + 1] : current.y + nums[i + 1];
						const x = abs ? nums[i + 2] : current.x + nums[i + 2];
						const y = abs ? nums[i + 3] : current.y + nums[i + 3];
						const x1 = lastCubicCp ? 2 * current.x - lastCubicCp.x : current.x;
						const y1 = lastCubicCp ? 2 * current.y - lastCubicCp.y : current.y;
						result += ` .. controls ${this.coord(x1, y1)} and ${this.coord(x2, y2)} .. ${this.coord(x, y)}`;
						lastCubicCp = { x: x2, y: y2 };
						current = { x, y };
						i += 4;
					}
					break;

				case 'Q':
					while (i < nums.length) {
						const x1 = abs ? nums[i] : current.x + nums[i];
						const y1 = abs ? nums[i + 1] : current.y + nums[i + 1];
						const x = abs ? nums[i + 2] : current.x + nums[i + 2];
						const y = abs ? nums[i + 3] : current.y + nums[i + 3];
						const cp1x = current.x + (2 / 3) * (x1 - current.x);
						const cp1y = current.y + (2 / 3) * (y1 - current.y);
						const cp2x = cp1x + (x - current.x) / 3;
						const cp2y = cp1y + (y - current.y) / 3;
						result += ` .. controls ${this.coord(cp1x, cp1y)} and ${this.coord(cp2x, cp2y)} .. ${this.coord(x, y)}`;
						lastQuadCp = { x: x1, y: y1 };
						current = { x, y };
						i += 4;
					}
					break;

				case 'T':
					while (i < nums.length) {
						const x = abs ? nums[i] : current.x + nums[i];
						const y = abs ? nums[i + 1] : current.y + nums[i + 1];
						const x1 = lastQuadCp ? 2 * current.x - lastQuadCp.x : current.x;
						const y1 = lastQuadCp ? 2 * current.y - lastQuadCp.y : current.y;
						const cp1x = current.x + (2 / 3) * (x1 - current.x);
						const cp1y = current.y + (2 / 3) * (y1 - current.y);
						const cp2x = cp1x + (x - current.x) / 3;
						const cp2y = cp1y + (y - current.y) / 3;
						result += ` .. controls ${this.coord(cp1x, cp1y)} and ${this.coord(cp2x, cp2y)} .. ${this.coord(x, y)}`;
						lastQuadCp = { x: x1, y: y1 };
						current = { x, y };
						i += 2;
					}
					break;

				case 'A':
					while (i < nums.length) {
						const x = abs ? nums[i + 5] : current.x + nums[i + 5];
						const y = abs ? nums[i + 6] : current.y + nums[i + 6];
						result += ` -- ${this.coord(x, y)}`;
						current = { x, y };
						i += 7;
					}
					break;

				case 'Z':
					result += ' -- cycle';
					current = { ...start };
					break;
			}
		}

		return result;
	}

	// ==================== Style & Transform ====================

	getStyleOptions(node) {
		const style = node.getAttribute('style') || '';
		const props = this.parseStyle(style);
		const options = [];

		const fill = node.getAttribute('fill') || props.fill;
		if (fill && fill !== 'none') {
			if (fill.startsWith('url(')) {
				options.push('fill=black');
			} else {
				options.push(`fill=${this.convertColor(fill)}`);
			}
		} else if (fill === 'none') {
			options.push('fill=none');
		} else if (['path', 'rect', 'circle', 'ellipse', 'polygon', 'polyline'].includes(node.tagName.toLowerCase())) {
			options.push('fill=black');
		}

		const stroke = node.getAttribute('stroke') || props.stroke;
		if (stroke && stroke !== 'none') {
			options.push(`draw=${this.convertColor(stroke)}`);
		}

		const sw = node.getAttribute('stroke-width') || props['stroke-width'];
		if (sw) {
			options.push(`line width=${this.round(this.toUnit(parseFloat(sw)))}${this.settings.outputUnit}`);
		}

		const op = node.getAttribute('opacity') || props.opacity;
		if (op && parseFloat(op) < 1) {
			options.push(`opacity=${op}`);
		}

		const fop = node.getAttribute('fill-opacity') || props['fill-opacity'];
		if (fop && parseFloat(fop) < 1) {
			options.push(`fill opacity=${fop}`);
		}

		const sop = node.getAttribute('stroke-opacity') || props['stroke-opacity'];
		if (sop && parseFloat(sop) < 1) {
			options.push(`draw opacity=${sop}`);
		}

		const cap = node.getAttribute('stroke-linecap') || props['stroke-linecap'];
		if (cap) {
			options.push(`line cap=${cap}`);
		}

		const join = node.getAttribute('stroke-linejoin') || props['stroke-linejoin'];
		if (join) {
			options.push(`line join=${join}`);
		}

		const dash = node.getAttribute('stroke-dasharray') || props['stroke-dasharray'];
		if (dash && dash !== 'none') {
			const parts = dash.split(/[\s,]+/).map(parseFloat);
			const dashes = [];
			for (let j = 0; j < parts.length; j++) {
				const val = this.round(this.toUnit(parts[j]));
				dashes.push(`${j % 2 === 0 ? 'on' : 'off'} ${val}${this.settings.outputUnit}`);
			}
			options.push(`dash pattern=${dashes.join(' ')}`);
		}

		if (this.settings.markings !== 'ignore') {
			const ms = node.getAttribute('marker-start') || props['marker-start'];
			const me = node.getAttribute('marker-end') || props['marker-end'];
			if (ms || me) {
				const start = ms ? (ms.includes('end') ? `${this.settings.arrowStyle} reversed` : this.settings.arrowStyle) : '';
				const end = me ? (me.includes('start') ? `${this.settings.arrowStyle} reversed` : this.settings.arrowStyle) : '';
				if (start && end) {
					options.push(`${start}-${end}`);
				} else if (start) {
					options.push(`${start}-`);
				} else if (end) {
					options.push(`-${end}`);
				}
			}
		}

		return options.join(',');
	}

	getTransformOptions(node, isNode = false) {
		const transform = node.getAttribute('transform');
		if (!transform) return '';

		const options = [];
		const regex = /(translate|rotate|scale|matrix)\(([^)]+)\)/g;
		let match;

		while ((match = regex.exec(transform)) !== null) {
			const type = match[1];
			const vals = match[2].split(/[\s,]+/).map(parseFloat);

			switch (type) {
				case 'translate':
					const tx = this.round(this.toUnit(vals[0]));
					const ty = vals[1] !== undefined ? this.round(this.toUnit(vals[1])) : 0;
					const tyFinal = this.settings.reverseY && !isNode ? -ty : ty;
					options.push(`shift={(${tx}${this.settings.outputUnit}, ${tyFinal}${this.settings.outputUnit})}`);
					break;
				case 'rotate':
					const ang = this.round(-vals[0]);
					if (vals.length >= 3) {
						const rx = this.round(this.toUnit(vals[1]));
						const ry = this.round(this.toUnit(vals[2]));
						const ryFinal = this.settings.reverseY ? this.height - ry : ry;
						options.push(`rotate around={${ang}:(${rx}${this.settings.outputUnit}, ${ryFinal}${this.settings.outputUnit})}`);
					} else {
						options.push(`rotate=${ang}`);
					}
					break;
				case 'scale':
					const sx = this.round(vals[0]);
					const sy = vals[1] !== undefined ? this.round(vals[1]) : sx;
					if (sx === sy) {
						options.push(`scale=${sx}`);
					} else {
						options.push(`xscale=${sx},yscale=${sy}`);
					}
					break;
				case 'matrix':
					const a = this.round(vals[0]);
					const b = this.round(vals[1]);
					const c = this.round(vals[2]);
					const d = this.round(vals[3]);
					const e = this.round(this.toUnit(vals[4]));
					const f = this.round(this.toUnit(vals[5]));
					const fFinal = this.settings.reverseY && !isNode ? -f : f;
					options.push(`cm={${a},${b},${c},${d},(${e}${this.settings.outputUnit}, ${fFinal}${this.settings.outputUnit})}`);
					break;
			}
		}

		return options.join(',');
	}

	parseStyle(styleStr) {
		const props = {};
		if (!styleStr) return props;
		const parts = styleStr.split(';');
		for (const part of parts) {
			const [k, v] = part.split(':');
			if (k && v) props[k.trim()] = v.trim();
		}
		return props;
	}

	convertColor(color) {
		if (!color) return 'black';
		color = color.trim().toLowerCase();

		const namedColors = ['black', 'red', 'green', 'blue', 'cyan', 'yellow', 'magenta', 'white', 'gray', 'orange', 'purple', 'brown', 'pink', 'lime', 'teal', 'olive', 'violet'];
		if (namedColors.includes(color)) return color;

		if (color.startsWith('#')) {
			const hex = color.slice(1);
			let r, g, b;
			if (hex.length === 3) {
				r = parseInt(hex[0] + hex[0], 16);
				g = parseInt(hex[1] + hex[1], 16);
				b = parseInt(hex[2] + hex[2], 16);
			} else if (hex.length === 6) {
				r = parseInt(hex.slice(0, 2), 16);
				g = parseInt(hex.slice(2, 4), 16);
				b = parseInt(hex.slice(4, 6), 16);
			} else {
				return 'black';
			}
			const name = `c${hex}`;
			if (!this.colors.includes(name)) {
				this.colors.push(name);
				this.colorCode += `\\definecolor{${name}}{RGB}{${r},${g},${b}}\n`;
			}
			return name;
		}

		const rgbMatch = color.match(/rgb\((\d+),\s*(\d+),\s*(\d+)\)/);
		if (rgbMatch) {
			const r = parseInt(rgbMatch[1]);
			const g = parseInt(rgbMatch[2]);
			const b = parseInt(rgbMatch[3]);
			const name = `crgb${r}${g}${b}`;
			if (!this.colors.includes(name)) {
				this.colors.push(name);
				this.colorCode += `\\definecolor{${name}}{RGB}{${r},${g},${b}}\n`;
			}
			return name;
		}

		return color;
	}

	coord(x, y) {
		const ux = this.round(this.toUnit(x));
		const uy = this.settings.reverseY ? this.round(this.toUnit(this.height - y)) : this.round(this.toUnit(y));
		return `(${ux}, ${uy})`;
	}

	toUnit(val) {
		const unit = this.settings.outputUnit;
		if (unit === 'cm') return val * 0.026458;
		if (unit === 'mm') return val * 0.26458;
		if (unit === 'in') return val * 0.010416;
		if (unit === 'pt') return val * 0.75;
		if (unit === 'px') return val;
		if (unit === 'pc') return val * 0.0625;
		if (unit === 'm') return val * 0.00026458;
		if (unit === 'Q') return val * 0.705;
		return val;
	}

	round(val) {
		if (typeof val !== 'number') return val;
		const factor = Math.pow(10, this.settings.roundNumber);
		return Math.round(val * factor) / factor;
	}

	escapeTex(str) {
		return str
			.replace(/\\/g, '\\textbackslash{}')
			.replace(/\$/g, '\\$')
			.replace(/%/g, '\\%')
			.replace(/_/g, '\\_')
			.replace(/#/g, '\\#')
			.replace(/&/g, '\\&')
			.replace(/{/g, '\\{')
			.replace(/}/g, '\\}')
			.replace(/\^/g, '\\^{}')
			.replace(/~/g, '\\textasciitilde{}');
	}
}

window.SvgToTikzConverter = SvgToTikzConverter;

